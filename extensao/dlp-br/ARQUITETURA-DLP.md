# DLP — diagnóstico, decisão de arquitetura e ordem de construção

Documento de projeto. Escrito em 2026-08-31, depois de o operador apontar que
o que existia **não era DLP**. Ele estava certo, e a prova está na seção 1.

Regra que vale para tudo aqui, a mesma já registrada para o GLPI:
**integração ≠ implementação ≠ provisionamento.** O conector, a extensão e o
plugin são construídos por inteiro mesmo que não haja nada do outro lado para
conectar. São duas coisas distintas.

Restrições do operador, que moldam todo o resto:

* **tudo dentro e integrado ao eXo. Nenhum painel externo.** Console,
  políticas, incidentes e gestão dos agentes vivem no portal.
* nenhuma ação destrutiva; só criar, aumentar, implementar, acrescentar.
* nada de valor chumbado, e nada de ajuste direto em banco para mascarar
  funcionamento.

---

## 1. O diagnóstico, com a prova

### 1.1 O que a tela de Quarentena mostrava

Três itens, coluna "Palavra-chave detectada" com `CPF` e `CPF, NOME`, e a
coluna "Conteúdo" **vazia em dois deles**.

### 1.2 O teste que provou o problema

Documento gravado com um **CPF válido** (dígito verificador correto) e **sem a
palavra "CPF", "CNPJ" ou "NOME"** em lugar nenhum:

```
Memorando 44/2026.
O servidor matricula 8812 informou o numero 529.982.247-25 para deposito.
```

| Pergunta | Resposta medida |
|---|---|
| O motor de padrão detectou? | **Sim** — `REGISTROU ... classificacao=SIGILOSO SIGILOSO: CPF x1` |
| Foi para a quarentena? | **Não** — `DLP_POSITIVE_ITEMS` permaneceu com 3 linhas |
| Continua na pasta do usuário, indexado e compartilhável? | **Sim** |

E o inverso: os três itens que **estavam** na tela foram parar lá porque o
texto continha a **palavra** "CPF"/"NOME" — casamento literal do detector
nativo contra a lista `CPF, CNPJ, NOME` cadastrada na tela.

**Conclusão:** documento com o número e sem a palavra passa livre; documento
com a palavra e sem número nenhum vai para a quarentena. Está invertido em
relação ao que importa. O dado sensível é o **número**, não a sigla.

### 1.3 As três causas, lidas no código e no bytecode

**(a) A política nunca age.** `PoliticaDlp.padrao()` nasce em `Acao.ALERTAR`, e
o conector só chama `treatItem(...)` — a mecânica real de quarentena — quando
`decisao.impedeOperacao()`. Em `ALERTAR` isso é falso: escreve uma linha de log
e nada mais. Medido: `RETIROU DE CIRCULACAO` = 0 ocorrências; `REGISTROU` = 129.

**(b) `QUARENTENAR` é uma ação inerte.** O enum `PoliticaDlp.Acao` declara
`REGISTRAR, ALERTAR, BLOQUEAR, QUARENTENAR`, mas

```java
public boolean impedeOperacao() {
  return acao == Acao.BLOQUEAR;
}
```

`QUARENTENAR` não entra nessa condição. Quem configurasse a política em
`QUARENTENAR` — que é o nome que descreve exatamente o que se quer — teria o
mesmo efeito de `REGISTRAR`: nenhum. É defeito, não decisão.

**(c) O registro nativo é estruturalmente incapaz de guardar a evidência.**
Em `FileDlpConnector.saveDlpPositiveItem`, lido no bytecode:

```java
entity.setReference(node.getUUID());
entity.setTitle(node.hasProperty("exo:title")
                ? URLDecoder.decode(node.getProperty("exo:title").getString(), "UTF-8")
                : null);
entity.setAuthor(node.hasProperty("exo:lastModifier") ? ... : null);
entity.setType("file");
entity.setDetectionDate(Calendar.getInstance());
entity.setKeywords(getDetectedKeywords(collection, dlpOperationProcessor.getKeywords()));
```

Duas consequências:

* **`ITEM_TITLE` fica `NULL`** para todo nó sem `exo:title` — é a razão da
  coluna "Conteúdo" vazia. Não é falha da tela.
* **`KEYWORDS` é a interseção do resultado com a lista de palavras
  configurada.** Ou seja: o campo só sabe conter *rótulo de palavra-chave*. Não
  há onde gravar o achado mascarado, a posição, o detector, a severidade nem o
  canal. O `Mascarador` da extensão já produz `CPF 529.***.**7-25`, e essa
  informação **não tem coluna para onde ir**.

O ponto (c) é o que decide a arquitetura: **não dá para reaproveitar o
incidente nativo.** É preciso um modelo de incidente próprio — e, por
consequência, um console próprio, dentro do portal.

---

## 2. O que já existe e funciona (não será reescrito)

O núcleo `br.pmo.dlp` é sólido; o que faltava era fiação. Já existe:

* **11 detectores com validação de dígito verificador** — `CPF`, `CNPJ`,
  `CARTAO_CREDITO`, `PIS_PASEP`, `TITULO_ELEITOR`, `CNH`,
  `CHAVE_PIX_ALEATORIA`, `CEP`, `EMAIL`, `TELEFONE`,
  `SEGREDO_EM_TEXTO_CLARO`.
* **`Mascarador`** — mascaramento por tipo, já pronto (`mascarar`,
  `mascararTexto`).
* **`PoliticaDlp`** — severidade, mínimo de ocorrências, ação por achado e ação
  quando a extração foi parcial.
* **`CategoriaConformidade`**, **`Classificacao`**, **`RelatorioConformidade`**,
  **`InstantaneoConformidade`**.
* **`Extrator` / `ExtratorTextoSimples` / `ExtratorOcr`** — extração com marca
  de extração parcial (é o que impede PDF digitalizado de passar por "limpo").
* **Descoberta em repouso** — a varredura do acervo JCR, via
  `AcaoEnfileirarDlp` + fila do add-on.

---

## 3. Decisão de arquitetura

**Tudo dentro do eXo.** O portal é o DLP Manager, o DLP Server e o repositório
de incidentes. Os componentes que, na arquitetura Forcepoint, moram fora do
servidor — agente de endpoint, gateway ICAP, inspeção de e-mail — entram aqui
como **integração**: o eXo publica o contrato (API REST, registro do agente,
política distribuível, recebimento de incidente) e a extensão está completa e
testável mesmo sem nenhum agente instalado do outro lado.

```
                    ┌──────────────────────────────────────────┐
                    │            eXo Platform                  │
                    │                                          │
  documento no ────►│  Crawler (JCR)  ─┐                       │
  acervo            │                  │                       │
                    │  Gravação  ──────┤► MOTOR br.pmo.dlp     │
  upload/save ─────►│  (tempo real)    │  detectores+validador │
                    │                  │  máscara+política     │
                    │                  ▼                       │
                    │           INCIDENTE (tabela própria)     │
                    │           achado mascarado, posição,     │
                    │           detector, severidade, canal,   │
                    │           usuário, destino, ação tomada  │
                    │                  │                       │
                    │                  ├──► CONSOLE (portlet)  │
                    │                  ├──► RELATÓRIO / CSV    │
                    │                  └──► SIEM (syslog CEF)  │
                    │                  ▲                       │
                    │        API REST  │  /rest/dlp-pmo/*      │
                    └──────────────────┼───────────────────────┘
                                       │
             ┌─────────────────────────┼─────────────────────────┐
             │                         │                         │
      Agente de endpoint        Gateway ICAP              Inspeção de e-mail
      (conector pronto;         (conector pronto;         (conector pronto;
       agente é implantação      gateway é implantação     ligado ao caminho
       de parque)                de rede)                  SMTP da stack)
```

### Mapa Forcepoint → onde vive aqui

| Componente Forcepoint | Onde vive |
|---|---|
| DLP Manager (console) | Portlet no portal, em Configurações da plataforma › Segurança |
| DLP Server (motor) | `br.pmo.dlp` + `zz-dlp-br.jar`, dentro do eXo |
| DLP Crawler (dados em repouso) | Varredura JCR já existente |
| DLP Endpoint Agent | **Conector + API + registro de agente** no eXo |
| DLP Protector (ICAP) | **Conector + API** no eXo |
| DLP Email Security | **Conector + API**, ligado ao caminho SMTP da stack |
| Cloud Applications | **Conector + API** no eXo |

---

## 4. O que é honestamente inalcançável, e por quê

Não vou fingir o contrário. Os itens abaixo **exigem software rodando fora do
eXo** — na estação do servidor público ou no caminho de rede. O que se entrega
aqui é o **conector** dentro do portal; o agente ou o gateway em si é decisão
de infraestrutura da prefeitura, com implantação em parque:

* controle de dispositivo USB, clipboard, impressora, mídia óptica;
* varredura de disco local e de cliente de e-mail desktop;
* inspeção em linha de HTTP/HTTPS e FTP;
* varredura de CIFS/SMB/NFS, SQL Server/Oracle, caixas Exchange/O365/Gmail.

Também fora: **classificador por machine learning**. O operador decidiu, em
caráter permanente, que não há LLM local nesta stack, e um classificador
estatístico treinado localmente seria outro projeto. O que entra no lugar, e é
determinístico e auditável: regex + dicionário + validação por dígito
verificador/Luhn + `EDM` e `IDM` por hash.

---

## 5. Ordem de construção

Cada etapa entrega algo verificável na tela por um usuário real, e nenhuma
depende de agente instalado.

1. **A política agir.** `QUARENTENAR` deixa de ser inerte; severidade alta
   passa a retirar de circulação de fato. É o que separa auditoria de DLP.
2. **Incidente próprio** — tabela, entidade, DAO e serviço, com achado
   mascarado, posição, detector, severidade, canal, usuário, destino e ação.
3. **Bloqueio em tempo real** na gravação, com mensagem ao usuário.
4. **Console no portal** — lista, filtro, detalhe do incidente com a evidência
   mascarada, workflow (atribuir, revisar, escalar), trilha de auditoria.
5. **Política em tela** — sem editar arquivo de propriedades.
6. **Relatórios e exportação** — conformidade, CSV, e SIEM via syslog CEF.
7. **API REST de recepção** — `/rest/dlp-pmo/*`, para agente, ICAP e e-mail
   reportarem incidente e baixarem política.
8. **Registro e monitoramento de agente** dentro do console.
9. **Tipo real de arquivo** por assinatura (magic bytes), não por extensão.
10. **OCR** ligado ao caminho de extração já existente.
11. **EDM/IDM** — impressão digital de documento e casamento de dado
    estruturado.

---

## 6. Validação

Por decisão do operador, a validação é feita em **navegador real
automatizado** (Playwright, que a suíte já usa), com captura de tela de cada
passo — não por `curl` nem por chamada de API. Teste que não passa pela tela
não conta como prova.
