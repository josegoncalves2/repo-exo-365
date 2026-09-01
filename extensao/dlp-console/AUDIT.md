
---

### [218] 2026-09-01 09:00 -03 — A TELA DO CONSOLE, DENTRO DO PORTAL
**Acao:** Construcao de `extensao/dlp-console` — o portlet que faltava. Ate'
aqui a API estava completa e provada e a administracao do DLP so' era possivel
por chamada REST na mao; um administrador de prefeitura nao opera politica de
vazamento por `curl`.
**Comando/Arquivo:** `extensao/dlp-console/` (nucleo `Json`/`Html`/`Pagina`/`Tela` + `exo/PortletConsoleDlp`), `docker-compose.yml`
**Resultado:** onze abas — Painel, Incidentes, Revisao, Quarentena, Politica,
Indices, Dicionarios, Descoberta, Avisos, Agentes e Auditoria — em
**Administracao > Seguranca > Protecao de dados (DLP)**. Renderizado no
SERVIDOR, por tres razoes: o token da API nunca chega ao navegador; o servico
de DLP nao tem porta publicada; e a montagem do HTML (onde mora o escape, e
portanto o XSS) fica num nucleo que se prova no host, com 73 asseveracoes,
antes de o WAR existir. A tela funciona com JavaScript desligado.

A coluna que mais importa e' a de acoes: mostra o que a regra PEDIU e o que
ACONTECEU, com a acao nao cumprida **riscada**. E' o que distingue politica de
encenacao numa olhada.

**QUATRO DEFEITOS MEUS NO CAMINHO, e o que cada um ensinou:**
1. **A pagina nao era criada, em silencio.** Usei `templateLocation` com
   `importMode=merge` e `override=false`. Com `override=false` o importador
   considera o site `administration` ja' importado e nao olha o pacote novo:
   `PORTAL_PAGES` seguia sem a pagina depois de dois arranques limpos e **nao
   havia uma linha de erro no log**. Corrigido para a forma que o proprio
   `ai-agent.war` da imagem usa: `location` + `override=true` +
   `importMode=insert` (insere o que falta, nao toca no que existe).
2. **Faltava o `META-INF/exo-conf/configuration.xml`.** Sem ele o kernel NUNCA
   le o `WEB-INF/conf/configuration.xml` do WAR — o portlet carrega (quem o
   carrega e' o contentor de portlets) e a pagina nunca nasce. O WAR precisa se
   declarar dependencia do portal container. A linha que prova que funcionou e'
   `Including addon configuration file ar:/opt/exo/webapps/...!/META-INF/...`.
3. **Parametro cru na URL nao chega a um portlet.** Os links de detalhe eram
   `url("incidentes") + "&detalhe=" + id`, e o detalhe simplesmente nao abria:
   o contentor entrega apenas o que foi codificado por
   `PortletURL.setParameter`. Passaram a ser MODELOS com o marcador `__ID__`,
   montados pelo portlet e substituidos na tela. O formulario de filtros virou
   POST para a ActionURL, com os filtros guardados como parametros de render.
4. **Apostrofo dentro de `bash -c '...'` truncava o script de empacotamento.**
   Um `e'` num COMENTARIO fechava a string e o container recebia um script
   cortado, produzindo `cp: cannot stat /web/WEB-INF` numa montagem que
   existia. O erro apontava para o lugar errado. O bloco agora e' livre de
   apostrofos, com o motivo escrito nele.
**Status:** OK — pagina `console-dlp` confirmada em `PORTAL_PAGES` e no' de
navegacao `home/security/console-dlp` confirmado em `PORTAL_NAVIGATION_NODES`.

---

### [219] 2026-09-01 09:00 -03 — DEFEITO EM PRODUCAO: "OBSERVACAO" NAO ERA OBSERVACAO
**Acao:** Medicao do estado do servico depois do primeiro deploy revelou, com
o portal em MODO OBSERVACAO (`EXO_DLP_SAIDA_APLICAR=false`), **1 item RETIDO no
cofre e 1 aviso ENVIADO ao administrador**. O modo promete que nada muda para o
usuario; o que acontecia e' que o portal nao bloqueava e o SERVICO agia mesmo
assim.
**Causa:** `registrar` e `efeitos` estavam amarrados num parametro so'. Nao
havia como pedir "grave o incidente e NAO faca nada".
**Comando/Arquivo:** `dlp/servico.py`, `dlp/servidor.py`, `dlp/incidentes/modelo.py`, `extensao/dlp-saida/src/br/pmo/dlpsaida/{ClienteDlp.java,exo/FiltroSaidaDlp.java}`
**Resultado:** os dois passaram a ser independentes, e o portal agora DIZ ao
servico que esta' observando (`"observacao": true` no contexto). Em observacao:
o incidente e' gravado com `modo=OBSERVACAO`, `acoes_executadas` fica **vazio**
e o que aconteceria vai para `acoes_simuladas`; nada e' retido, nenhum e-mail
sai, nenhum conteudo transformado e' devolvido, e a cifra nem chega a ser
calculada. A classificacao do recurso continua sendo gravada, porque ela e' o
MAPA ("onde estao os dados"), e nao uma acao. A trilha do incidente registra
`ACAO_SIMULADA` com o texto "em observacao, NADA foi feito".

Registrar como "executado" o que ninguem executou e' o que torna um relatorio
de conformidade inutil — e era exatamente o defeito que este trabalho existe
para desfazer, repetido por mim num lugar diferente.
**Status:** OK — coberto por caso de teste proprio na suite do motor.
