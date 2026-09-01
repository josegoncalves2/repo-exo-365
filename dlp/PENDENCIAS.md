# DLP — o que está pronto e o que ainda NÃO está

Auditoria honesta, atualizada em **2026-09-01**. A versão anterior deste
arquivo (2026-08-31) listava dez ações declaradas com duas executando, dois
DLPs paralelos que não se falavam, canais no ar sem tráfego e um console sem
tela. **Este documento registra o que foi fechado desde então e o que
permanece aberto.** Onde há contagem, ela veio de varredura do próprio
repositório, não de memória.

> Critério, o mesmo de antes: se um nome existe numa lista mas nenhum código o
> executa, isso é **encenação** — parece recurso e não é.

---

## 1. AÇÕES DA POLÍTICA — todas executam

`politica/modelo.py` declara 10 ações. Situação real hoje:

| Ação | Estado | Onde acontece |
|---|---|---|
| `BLOQUEAR` | **funciona** | `acoes/executor.py`; provado em navegador |
| `QUARENTENAR` | **funciona** | retém a cópia **cifrada** no cofre (`motor/cofre.py`, AES-256-GCM, chave por item), abre registro de custódia com sha256 do claro, e tem **caminho de volta**: liberar cria autorização nominal e a mesma transferência passa |
| `REVISAO_MANUAL` | **funciona** | fila real em `/revisao`; aprovar cria liberação com prazo e contagem de usos; a transferência seguinte passa e o uso é consumido |
| `MASCARAR` | **funciona** | o executor devolve o conteúdo redigido e **o filtro Java o entrega** (`FiltroSaidaDlp.entregarTransformado`). Antes o serviço devolvia `texto_mascarado` e o portal nunca o usava |
| `CRIPTOGRAFAR` | **funciona** | ZIP AES-256 no padrão WinZip AE-2, **verificado pelo 7-Zip** na suíte; senha vai ao usuário por canal separado. No canal de e-mail, S/MIME (CMS EnvelopedData) para o certificado do destinatário |
| `NOTIFICAR_USUARIO` | **funciona** | fila persistente + carteiro com reenvio e recuo exponencial; e-mail sai de verdade |
| `NOTIFICAR_ADMIN` | **funciona** | idem, para `DLP_EMAIL_ADMINISTRADORES` |
| `ORIENTAR` | **funciona** | texto de coaching por regra (`Regra.orientacao`), enviado ao usuário e exibido na página de bloqueio |
| `PERMITIR` / `REGISTRAR` | funcionam | aparecem em `acoes_executadas` para que a tela não mostre ação da regra sem correspondência |

**O que mudou de estrutura:** entre *decidir* e *registrar* passou a existir
**executar**. O incidente guarda dois campos distintos — `acoes` (o que a regra
pediu) e `acoes_executadas` (o que aconteceu) — e um terceiro,
`acoes_nao_aplicaveis`, com o motivo de cada ação que não foi possível cumprir.

**Degradação honesta:** mascarar dentro de um PDF ou de um `.docx` corromperia
o arquivo entregue. Nesses casos a ação **não** é dada por cumprida: o executor
degrada para `DLP_ACAO_NAO_APLICAVEL` (padrão `BLOQUEAR`, falha fechada) e grava
o motivo no incidente e na trilha.

---

## 2. CANAIS

| Canal | Produtor real |
|---|---|
| `DOWNLOAD`, `LINK_PUBLICO`, `COMPARTILHAMENTO_EXTERNO`, `EDITOR`, `WEBDAV` | `FiltroSaidaDlp` — funcionam |
| `EMAIL`, `EMAIL_INTERNO` | proxy SMTP (`canais/correio.py`) — **exercitado ponta a ponta** na suíte, com socket real |
| `ICAP` | gateway (`canais/icap.py`) — **exercitado ponta a ponta** na suíte |
| `DESCOBERTA` | **crawler próprio** (`descoberta/`), gravando no MESMO banco de incidentes |
| `API` | `/analisar` |
| `CHAT`, `NUVEM`, `IMPRESSAO`, `USB`, `CLIPBOARD`, `ENDPOINT` | **ainda sem produtor** — ver item 5 |

`EMAIL_INTERNO` era produzido pelo proxy SMTP **sem estar catalogado**: entrava
pelo adaptador interno (que não valida) e teria sido recusado como canal
inválido se viesse pela API. Agora é canal declarado, e a política trata
circulação interna e envio externo como eventos diferentes.

**Os dois DLPs paralelos deixaram de existir como problema:** a descoberta do
serviço novo entra pelo mesmo `ServicoDlp.analisar`, com a mesma política e o
mesmo acervo de incidentes. A extensão antiga `dlp-br` continua instalada e
opera o add-on nativo da eXo; ela não é mais a única varredura do acervo.

---

## 3. DEFEITOS ENCONTRADOS AO LIGAR OS CANAIS

Ligar ICAP e SMTP ao tráfego real pela primeira vez revelou três defeitos que
existiam desde que o código foi escrito:

1. **ICAP travava a conexão em vez de responder 405.** `handle()` chamava
   `self._responder(...)`, um método que **não existia** na classe. O
   `AttributeError` caía no `except` de cima, que tentava responder pelo mesmo
   método inexistente e engolia a segunda falha. Corrigido (`_estado`).
2. **A máscara no e-mail nunca acontecia.** A entrega usava
   `veredito.get("mensagem_final", bruto)` e **nada, em lugar nenhum, escrevia
   `mensagem_final`**. O padrão valia sempre: a mensagem seguia inteira, e o
   incidente registrava "MASCARAR". Agora a mensagem é remontada parte a parte.
3. **Entrega ao relay sem TLS.** Um DLP que inspeciona o anexo e entrega em
   texto claro na rede protege contra o usuário e não contra a rede.
   `DLP_SMTP_STARTTLS_SAIDA` liga STARTTLS com verificação de cadeia.

Somam-se dois achados do extrator: um item ilegível dentro de um compactado
(ZIP AES, por exemplo) **abortava a leitura do pacote inteiro** — dez arquivos
deixavam de ser varridos por causa de um; e o `IndiceEdm` calculava e gravava
um conjunto de hashes de registro que **nunca era consultado**.

---

## 4. CÓDIGO MORTO — removido

* `motor/extracao.py` — `Texto.vazio_e_ilegivel`: **removido**.
* `motor/impressao.py` — `IndiceEdm._registros`: **removido** (era calculado,
  serializado no banco e nunca lido).
* `agente.politica_versao` — **passou a ser lido**: `/agentes` compara a versão
  gravada com a impressão digital da política vigente e marca o agente como
  desatualizado. Era o único motivo de o campo existir.
* `dlp/testes/` — **preenchido**: 92 casos, 285 asseverações.
* Importações órfãs nos 8 arquivos apontados — **removidas** (varredura
  automática confirma zero).
* `Incidente.anotacoes` — **passou a ter fluxo**: o console anota pela tela.

---

## 5. O QUE AINDA NÃO ESTÁ PRONTO

Esta é a lista honesta do que permanece aberto.

### Depende de software fora do eXo
1. **Agente de estação.** O conector está pronto e testável
   (`POST /agentes/registrar`, `POST /analisar`), a política é entregue no
   registro e o console mostra agentes desatualizados. **O agente em si não
   existe** — portanto **não há hoje inspeção de USB, área de transferência,
   impressora, mídia óptica nem disco local**.
2. **ICAP em linha depende do proxy da rede.** O gateway responde ICAP correto
   e está provado ponta a ponta com cliente real na suíte, mas **nenhum proxy
   da prefeitura aponta para ele**. Apontar o Squid/proxy para
   `exo-dlp:1344` é configuração de rede, fora deste container.
3. **Varredura de SQL Server/Oracle e de caixas Exchange/O365** não existe.
   CIFS/SMB e NFS são varridos pela `OrigemArquivos` apontada para o ponto de
   montagem — montar é tarefa do sistema operacional, e é assim que se faz.

### Dentro do produto
4. **Canal CHAT** — o Matrix/Synapse não é inspecionado.
5. **Canal NUVEM** — a extensão `nuvem` (Nextcloud) não chama o DLP.
6. **Editor de política campo a campo.** O console liga/desliga regra por
   botão e edita a política inteira como JSON com recuo. Um editor de
   condições campo a campo não existe.
7. **O portal continua em MODO OBSERVAÇÃO por padrão**
   (`EXO_DLP_SAIDA_APLICAR=false`): os incidentes são registrados e **nada muda
   para o usuário**. Ligar é uma variável de ambiente e uma recriação do
   `exo-app`. A ordem recomendada continua sendo: observar, ler os incidentes,
   ajustar regra e exceção no console, e só então aplicar.
8. **Descoberta desligada por padrão** (`DLP_DESCOBERTA_URL` vazio). Preencher
   URL, usuário e senha liga a varredura do acervo do portal por WebDAV.

---

## 6. O QUE EXISTE, EM NÚMEROS

| Componente | Tamanho | Provas |
|---|---|---|
| Serviço (Python, container `exo-dlp`) | motor, ações, canais, descoberta, API | **92 casos / 285 asseverações**, portão do build da imagem |
| `extensao/dlp-saida` (filtro + REST) | núcleo + adaptador | 37 asseverações no host |
| `extensao/dlp-console` (a TELA) | núcleo + portlet | 73 asseverações no host |

A suíte do serviço não usa framework de teste e roda **dentro do build da
imagem**: se uma asseveração falhar, a imagem não existe. ICAP e SMTP são
falados por socket de verdade; o ZIP cifrado é aberto pelo **7-Zip**; o S/MIME é
reaberto pela **chave privada do destinatário**. Um teste que confere o próprio
código contra si mesmo prova consistência, não funcionamento.

---

## 7. RESUMO EM UMA LINHA

**Funciona e está provado:** detecção com validação de dígito verificador, EDM,
IDM, classificador estatístico, tipo real, OCR, extração recursiva, política com
exceções, **as dez ações de resposta**, quarentena com cofre cifrado e
restauração, fila de revisão com liberação nominal, notificação por e-mail,
descoberta de dados em repouso, ICAP, proxy SMTP, incidente com evidência
mascarada, API, relatórios, SIEM e **a tela do console dentro do portal**.

**Não existe:** agente de estação (logo, nada monitora USB, impressora ou área
de transferência), inspeção de chat e de nuvem, varredura de bancos e de caixas
de e-mail.

**Está pronto e desligado por escolha:** a aplicação no portal (modo
observação) e a varredura de descoberta.
