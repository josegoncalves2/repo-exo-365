# Núcleo DLP próprio — `br.pmo.dlp`

Motor de detecção de dados pessoais e sensíveis brasileiros, **escrito do zero**
para esta instalação. Sem framework, sem dependência externa, sem rede: compila
e se prova com `javac` e `java`, e nada mais.

    ./construir.sh

## Por que existe, se o portal já tem um add-on de DLP

O `exo-dlp` 7.2.1 está instalado e funcionando — mas o que ele faz é **casar
palavra-chave** contra o índice do Elasticsearch e mandar o documento para a
quarentena. Duas consequências, ambas medidas nesta instalação:

1. **Hoje ele não faz nada.** `exo.dlp.keywords` está vazio em
   `conf/exo.properties`, de propósito: ninguém quis chutar uma lista de
   palavras contra um acervo em produção. Job rodando a cada 5 minutos, zero
   documento avaliado.
2. **Ligá-lo com palavras não resolve.** `CPF` como palavra-chave põe em
   quarentena todo ofício que *cite* a sigla. `\d{11}` casa com número de
   protocolo, matrícula e código de barras. O resultado é quarentena em massa de
   documento legítimo, e a administração desliga o DLP inteiro na semana
   seguinte — ficando com menos proteção do que tinha antes.

Este núcleo é a camada que falta: ele não procura a *palavra* CPF, procura um
**CPF que fecha no módulo 11**. Nada aqui remove ou substitui o add-on oficial.

## O que ele faz

| Item do briefing (2.1) | Onde está | Estado |
|---|---|---|
| Descoberta de dados sensíveis | `RegrasSensiveis`, `Varredura` | pronto |
| Classificação de dados | `Classificacao` (PÚBLICO/INTERNO/RESTRITO/SIGILOSO) | pronto |
| Mascaramento de dados | `Mascarador` | pronto |
| Gestão centralizada de políticas | `PoliticaDlp` | motor pronto; tela do portal falta |
| Bloqueio em tempo real | `PoliticaDlp.Acao.BLOQUEAR` | decisão pronta; ligação ao upload falta |
| Quarentena de arquivos | `PoliticaDlp.Acao.QUARENTENAR` | decisão pronta; usa a quarentena do add-on oficial |
| Relatórios de conformidade | `ResultadoVarredura` | dados prontos; relatório falta |
| Inspeção OCR | SPI `Extrator` | ponto de extensão pronto; **motor de OCR não existe** |
| Inspeção de e-mails | SPI `Extrator` | ponto de extensão pronto; **conector falta** |
| Monitoramento de endpoints, tráfego de rede, USB, nuvem, criptografia, UBA | — | **não existe** (ver "O que este núcleo não é") |

### As três decisões que sustentam o resto

**Todo achado de severidade ALTA confere dígito verificador.** CPF pelo módulo
11, CNPJ por dois módulos 11, cartão por Luhn, título de eleitor com validação de
UF, PIS e CNH pelos algoritmos próprios. Uma regra de formato aceita qualquer
sequência de dígitos; uma regra com aritmética aceita a sequência que *satisfaz*
a aritmética do documento — ordens de grandeza menos ruído.

**E-mail, telefone e CEP são severidade BAIXA.** Estão no rodapé de todo ofício.
Tratá-los como graves classificaria o acervo inteiro como sigiloso, e uma
classificação que vale para tudo não distingue nada. Eles sobem para RESTRITO
por **volume** (a partir de 50 ocorrências): um e-mail é assinatura, quatrocentos
são uma lista de contatos vazando.

**Varredura incompleta nunca é liberação.** Documento maior que o teto, ou que
estourou o orçamento de tempo, sai do motor com zero achado — igualzinho a um
documento limpo. Se a política olhasse só a lista de achados, o caminho trivial
de exfiltração seria empurrar o vazamento para depois do teto. Por isso
`ResultadoVarredura.isCompleta()` é campo de primeira classe e a política tem
ação própria para ele (`ALERTAR`, no padrão).

## O que este núcleo **não** é

Ser direto sobre isto importa mais do que a lista do que ele faz.

- **Não tem OCR.** A SPI `Extrator` tem o encaixe pronto, mas não há motor de
  OCR nesta stack. Ligar OCR exige um serviço próprio (Tesseract em container) —
  decisão de infraestrutura, com custo de RAM, que ainda não foi tomada.
- **Não vê endpoint, USB, tráfego de rede nem nuvem.** Esses quatro itens do
  briefing **não são implementáveis por uma extensão de portal**: exigem agente
  instalado na estação de trabalho e inspeção na borda da rede. O que cabe aqui
  é uma API de ingestão para receber eventos desses agentes — e ela ainda não
  foi escrita.
- **Não está ligado ao portal.** Este é o núcleo puro. O adaptador que roda
  dentro do eXo — extração via Tika/PDFBox/POI, interceptação de upload, REST e
  tela — é a fase seguinte, e compila contra os jars de `/opt/exo/lib`.

## As provas

`./construir.sh` compila com `-Xlint:all -Werror` e roda **129 asseverações**.
Falha em qualquer uma sai com código 1 e aborta o build.

O que elas cobrem, e por que cada bloco existe:

- **Recusa antes de aceite.** Para cada documento válido, trocar o último dígito
  pelos outros nove tem de derrubar a validação — a prova que um validador de
  formato não passa. Mais protocolo, matrícula, código de barras e sequência
  repetida, que são o volume real de um acervo.
- **Desduplicação.** `36084852955` é válido como CPF **e** como CNH;
  `12345678900` é válido como PIS **e** como CNH; um celular de 11 dígitos casa
  com o padrão de CPF. Sem desduplicar, o relatório de conformidade conta três
  onde há um — e número inflado destrói a confiança em todos os outros números
  do mesmo relatório.
- **Ida e volta do mascaramento.** O teste forte não é "o formato ficou bonito",
  é: **varrer de novo o texto mascarado não pode achar mais nada.** Máscara que
  continua casando com a regra é máscara que não mascarou.
- **Codificação.** CSV legado em ISO-8859-1 lido sem `U+FFFD`; BOM tratado;
  etiqueta de HTML vira espaço e nunca some — colar `<td>CPF</td><td>2</td>`
  sem separador quebra o módulo 11 e faria uma tabela inteira passar batida.
- **Extração impossível não vira string vazia.** PDF e ZIP renomeado para `.csv`
  levantam exceção. String vazia entra no motor e sai como "documento limpo".

### Um defeito que a prova pegou, registrado de propósito

`Mascarador.mascararTexto` percorria os achados na ordem de **severidade**, não
de **posição**. Ao encurtar um trecho anterior (`senha=umaSenhaLonga` → `senha=
***`), todos os índices seguintes deslocavam, e o CPF que vinha depois saía
**intacto** de um texto que se anunciava mascarado — vazamento silencioso, com
aparência de documento protegido. Corrigido em 2026-08-27: as ocorrências de
todos os achados são achatadas numa lista só e ordenadas por posição
decrescente. E a guarda que *escondia* o problema (pular ocorrência fora do
texto) virou exceção: falhar alto é obrigatório, porque devolver texto
parcialmente mascarado é pior do que não mascarar.

## Verificação de uso real (navegador, mouse e teclado)

O `construir.sh` é portão de compilação, **não é aceite**. O aceite da
funcionalidade só vale feito à mão, e só faz sentido depois que o adaptador do
portal existir. O roteiro está aqui desde já para não ser inventado depois:

1. Entrar em `https://192.168.1.59` com uma conta comum (não administrador).
2. Ir a um espaço, **Documentos → Enviar**, e subir um `.csv` com uma coluna de
   CPFs sintéticos (por exemplo `111.444.777-35`).
3. Conferir que o portal recusa o envio (política `BLOQUEAR`) ou aceita e alerta
   (política `ALERTAR`), **com o motivo em português e sem nenhum CPF visível na
   mensagem**.
4. Entrar como administrador em
   `/portal/administration/home/security/quarantine` e conferir que o item
   aparece, com rótulo, quantidade e amostra **mascarada**.
5. Repetir com um `.csv` de números de protocolo (`52601815908`) e conferir que
   **nada** acontece — se o passo 5 disparar, a instalação vai gerar falso
   positivo em massa e a política precisa de ajuste antes de endurecer.
