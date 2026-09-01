# DLP eXo — motor de protecao contra vazamento de dados

Container proprio na stack do eXo. **Nao tem painel proprio**: a gestao inteira
(politica, incidentes, indices, relatorios) e' feita DENTRO do portal, pelo
portlet de administracao, que consome esta API. E' o mesmo desenho de qualquer
outra integracao do projeto.

## O que ele faz, e o que nao faz

Protege dado **saindo**. Nao existe para impedir o servidor de subir documento
para o portal — o portal e' o lugar autorizado do dado. Existe para que aquele
dado nao saia por download, link publico, compartilhamento externo, e-mail,
chat, editor, nuvem, API, WebDAV, impressao, USB ou area de transferencia.

## Componentes

| Papel | Onde |
|---|---|
| Motor de deteccao e decisao | `motor/`, `politica/` — neste container |
| Repositorio de incidentes | `incidentes/` — SQLite em `/dados` |
| API REST | `servidor.py` — porta 8480 |
| Protector (inspecao em linha) | `canais/icap.py` — ICAP na 1344 |
| Email Security | `canais/correio.py` — proxy SMTP na 10025 |
| Console, politica, relatorios | **portlet dentro do eXo** (`extensao/dlp-console`), em Administracao > Seguranca > Protecao de dados (DLP) |
| Descoberta em repouso | `descoberta/` — WebDAV do portal e compartilhamento montado |
| Cofre da quarentena | `motor/cofre.py` — AES-256-GCM, chave por item |
| Agente de endpoint | conector pronto: `POST /agentes/registrar` + `POST /analisar` |

## Deteccao

Tres camadas, nessa ordem: **forma** (regex) -> **validacao** (digito
verificador) -> **contexto** (palavra proxima). A camada 2 e' o que separa
"tem a forma de CPF" de "e' um CPF". A 3 e' o que evita o falso positivo que
faz operador desligar DLP.

Detectores: CPF, CNPJ, cartao de pagamento (Luhn), PIS/PASEP, titulo de
eleitor, CNH, RENAVAM, CNS, IBAN, chave PIX, e-mail, telefone, CEP, segredo em
texto claro, dado de saude e dado sensivel do art. 5, II da LGPD.

Alem disso: **EDM** (casamento exato contra cadastro carregado, sem guardar o
cadastro — so' HMAC-SHA256 com sal da instalacao), **IDM** (impressao digital
de documento por janela deslizante), **classificador estatistico** Bayes+n-grama
treinado com exemplos da propria casa, **tipo real por assinatura**, **OCR** e
**extracao recursiva de compactados**.

## Regra que atravessa o codigo inteiro

**"Nao consegui ler" nunca vira "esta limpo".** PDF digitalizado sem OCR, zip
com senha, formato sem extrator: tudo sai marcado como extracao incompleta e
sobe de severidade em vez de passar por limpo. Falsa cobertura de DLP e' pior
do que DLP nenhum.

**A evidencia nunca guarda o valor.** O incidente registra tipo, quantidade,
posicao e um trecho com TODA a vizinhanca mascarada — inclusive achados
vizinhos ao alvo. Senao o proprio console viraria o vazamento.

**Acao declarada e acao cumprida sao campos DIFERENTES.** O incidente guarda
`acoes` (o que a regra pediu), `acoes_executadas` (o que aconteceu) e
`acoes_nao_aplicaveis` (o que nao foi possivel cumprir, com o motivo). Uma acao
que o motor nao consegue executar para aquele tipo de conteudo — mascarar
dentro de um PDF, por exemplo — **nao e' dada por cumprida**: degrada para
`DLP_ACAO_NAO_APLICAVEL` (padrao `BLOQUEAR`) e o motivo fica escrito. Registrar
como "executado" o que ninguem executou e' o que torna um relatorio de
conformidade inutil.

**Observacao quer dizer que nada muda.** Quando o portal esta em modo
observacao (`EXO_DLP_SAIDA_APLICAR=false`), o incidente e' gravado, o que
ACONTECERIA fica em `acoes_simuladas`, e nada com efeito colateral ocorre — nem
retencao no cofre, nem e-mail. Foi um defeito medido em producao em 2026-09-01:
enquanto registrar e executar estavam amarrados, a "observacao" retinha
arquivos e avisava administradores.

## As dez acoes de resposta

| Acao | O que faz |
|---|---|
| `PERMITIR` | deixa passar |
| `REGISTRAR` | grava o incidente (implicito em todas) |
| `NOTIFICAR_USUARIO` | e-mail ao autor da tentativa, sem o valor sensivel |
| `NOTIFICAR_ADMIN` | e-mail aos enderecos de `DLP_EMAIL_ADMINISTRADORES` |
| `ORIENTAR` | mensagem educativa (texto por regra), na tela e por e-mail |
| `MASCARAR` | entrega o conteudo com os valores redigidos |
| `CRIPTOGRAFAR` | ZIP AES-256 (senha por canal separado) ou S/MIME no e-mail |
| `QUARENTENAR` | retem a copia cifrada no cofre, com restauracao e liberacao |
| `REVISAO_MANUAL` | bloqueia e coloca na fila de revisao humana |
| `BLOQUEAR` | impede a saida |

A fila de avisos e' persistente, com reenvio de espera crescente e teto de
tentativas. O que esgota fica em **FALHA visivel no console**, nunca em
silencio.

## Configuracao (ambiente)

| Variavel | Padrao | Para que |
|---|---|---|
| `DLP_DADOS` | `/dados` | banco, sal e token |
| `DLP_TOKEN` | gerado | token da API (`X-DLP-Token`) |
| `DLP_PORTA_API` | `8480` | API REST |
| `DLP_ICAP` / `DLP_PORTA_ICAP` | `sim` / `1344` | gateway ICAP |
| `DLP_SMTP` / `DLP_PORTA_SMTP` | `sim` / `10025` | proxy SMTP |
| `DLP_SMTP_DESTINO_HOST/PORTA` | `mailpit` / `1025` | para onde entregar depois de inspecionar |
| `DLP_DOMINIOS_INTERNOS` | `pmeto.local` | e-mail entre estes dominios nao e' saida |
| `DLP_SIEM_HOST/PORTA/PROTOCOLO/FORMATO` | — / `514` / `udp` / `cef` | syslog CEF ou LEEF |
| `DLP_SMTP_STARTTLS_SAIDA` | `nao` | TLS na entrega ao relay, com verificacao de cadeia |
| `DLP_NOTIFICA_SMTP_HOST/PORTA` | `mailpit` / `1025` | para onde o DLP entrega os PROPRIOS avisos. **Nunca aponte para a porta 10025 do proprio DLP**: um aviso sobre e-mail bloqueado seria inspecionado e geraria outro aviso, indefinidamente |
| `DLP_NOTIFICA_REMETENTE` | `dlp@pmeto.local` | remetente dos avisos |
| `DLP_EMAIL_ADMINISTRADORES` | — | quem recebe `NOTIFICAR_ADMIN`. Vazio deixa os avisos em FALHA na fila, visiveis no console |
| `DLP_DOMINIO_EMAIL` | — | reserva quando o portal nao informa o e-mail do usuario |
| `DLP_URL_CONSOLE` | — | link do console nos avisos |
| `DLP_ACAO_NAO_APLICAVEL` | `BLOQUEAR` | o que fazer quando a acao pedida e impossivel para aquele conteudo |
| `DLP_DESCOBERTA_URL/USUARIO/SENHA` | — | acervo do portal por WebDAV. Vazio desliga |
| `DLP_DESCOBERTA_CAMINHOS` | — | `nome=/caminho` de compartilhamentos montados (CIFS/SMB, NFS) |
| `DLP_DESCOBERTA_INTERVALO` | `0` | segundos entre varreduras incrementais; `0` desliga o agendamento |

A **chave do cofre** (`chave-cofre.bin`) tambem e gerada no primeiro arranque,
com modo 600. Trocar essa chave torna ilegivel tudo o que ja esta retido em
quarentena — nao ha rotacao automatica de proposito.

## Provas

A suite propria do motor roda **dentro do `docker build`**: se uma asseveracao
falhar, a imagem nao existe. Onde ha verificacao independente, ela e usada — o
ZIP cifrado e aberto pelo **7-Zip**, o S/MIME e reaberto pela **chave privada
do destinatario**, e ICAP e SMTP sao falados por **socket real**.

    docker compose build dlp        # roda a suite como portao

O sal e o token sao gerados no primeiro arranque, com modo 600, e reusados.
**Trocar o sal invalida todo indice EDM/IDM** — por isso ele persiste no volume.
