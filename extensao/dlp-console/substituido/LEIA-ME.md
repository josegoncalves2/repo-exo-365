# Codigo substituido em 2026-09-01

Estes arquivos formavam o console de DLP na sua PRIMEIRA forma: um
`javax.portlet.GenericPortlet` que escrevia o proprio documento HTML, com a
propria folha de estilo embutida.

**Nao foram apagados de proposito.** `extensao/dlp-console/` esta' fora do
controle de versao (`git status` a mostra como nao rastreada), entao apagar
seria irreversivel, e a regra do projeto proibe acao destrutiva. Ficam aqui,
fora do caminho do build, ate' o operador decidir.

## Por que sairam

1. **Nao eram o padrao da plataforma.** Contagem feita no proprio container:
   dos 175 portlets instalados, 142 usam
   `org.exoplatform.commons.api.portlet.GenericDispatchedViewPortlet` e
   despacham para uma tela Vue. `PortletConsoleDlp` era o UNICO que desenhava a
   propria pagina.

2. **A tela nao herdava o skin.** Sem `gatein-resources.xml` com
   `<portlet-skin>`, `Pagina.java` era obrigada a trazer 80 linhas de `<style>`
   com paleta propria. Dai' o console destoar do portal inteiro, inclusive do
   add-on de DLP da propria eXo, na tela ao lado.

3. **As abas nunca trocavam.** A navegacao era por parametro de render. O log
   do servidor, a cada clique do operador:

       Console de DLP: render aba=painel parametros=[]

   Zero parametros chegavam ao portlet. A pagina vive no contentor
   `singlePageApplicationContainer`, que a plataforma reserva a aplicacoes de
   pagina unica que navegam NO CLIENTE. Navegacao server-side ali nao funciona.

## O que os substituiu

* `web/js/consoleDlp.bundle.js` — a tela, em Vue 2 / Vuetify 2.3.10, com as
  onze secoes trocando no cliente.
* `web/html/consoleDlp.html` — o stub que a plataforma despacha.
* `web/WEB-INF/gatein-resources.xml` — registra o skin `Enterprise` e o modulo.
* `ConsoleDlpRest` (no WAR `dlp-saida`) — ganhou as rotas das oito secoes que
  antes so' existiam dentro do portlet.

`Provas.java` (em `prova/`) provava o escape de HTML de `Html.java`. Como nao
ha' mais HTML montado em Java, o escape passou a ser responsabilidade do Vue,
que escapa por construcao em `{{ }}`. O unico ponto que ainda precisa de olho e'
qualquer `v-html`, e o console nao usa nenhum.
