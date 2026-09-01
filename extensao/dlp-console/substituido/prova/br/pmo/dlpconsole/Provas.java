package br.pmo.dlpconsole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provas do nucleo do console, executadas no HOST antes de o WAR existir.
 *
 * <p>Sao elas o portao de {@code construir.sh}: se uma asseveracao falhar,
 * nada e' empacotado. O que se prova aqui e' exatamente o que nao da' para
 * provar com o portal de pe' sem um navegador -- leitura de JSON e ESCAPE de
 * HTML. E' na montagem do HTML que mora o XSS, e num console que exibe nome de
 * arquivo enviado por usuario o XSS chega pela porta da frente.
 */
public final class Provas {

  private static int asseveracoes;
  private static int falhas;

  public static void main(String[] args) {
    json();
    jsonMalformado();
    jsonBonito();
    escape();
    telaCompleta();
    telaSemServico();
    acoesPedidasContraExecutadas();
    evidenciaMascarada();
    acessoNegado();

    System.out.println("  " + asseveracoes + " asseveracoes, " + falhas
                       + " falha(s)");
    if (falhas > 0) {
      System.exit(1);
    }
  }

  // ------------------------------------------------------------------- Json
  private static void json() {
    String bruto = "{\"total\": 3, \"ok\": true, \"nada\": null,"
                   + "\"itens\": [{\"nome\": \"a\\\"b\", \"n\": 1.5},"
                   + "{\"nome\": \"\\u00e7\\u00e3o\"}],"
                   + "\"vazio\": {}, \"lista_vazia\": []}";
    Map<String, Object> m = Json.objeto(bruto);
    igual("3", Json.texto(m, "total"),
          "numero inteiro tem de sair sem o '.0' que assustaria quem le a tela");
    igual(3L, Json.inteiro(m, "total", 0), "inteiro");
    certo(Json.logico(m, "ok", false), "logico verdadeiro");
    igual("", Json.texto(m, "nada"), "nulo vira texto vazio");
    List<Object> itens = Json.lista(m, "itens");
    igual(2, itens.size(), "dois itens");
    igual("a\"b", Json.texto(itens.get(0), "nome"),
          "aspas escapadas dentro do texto");
    igual("1.5", Json.texto(itens.get(0), "n"), "decimal preservado");
    igual("ção", Json.texto(itens.get(1), "nome"), "escape \\u decodificado");
    certo(Json.mapa(m, "vazio").isEmpty(), "objeto vazio");
    certo(Json.lista(m, "lista_vazia").isEmpty(), "lista vazia");
    igual("", Json.texto(m, "inexistente"), "chave ausente nao explode");
  }

  private static void jsonMalformado() {
    // A tela NAO pode quebrar por causa de uma resposta estranha: o console de
    // seguranca tem de continuar aberto justamente quando algo esta' errado.
    certo(Json.objeto("{isso nao e json").isEmpty(), "lixo vira mapa vazio");
    certo(Json.objeto("").isEmpty(), "vazio vira mapa vazio");
    certo(Json.objeto("[1,2,3]").isEmpty(), "vetor no lugar de objeto");
    boolean levantou = false;
    try {
      Json.ler("{\"a\":1}sobra");
    } catch (RuntimeException e) {
      levantou = true;
    }
    certo(levantou, "sobra depois do valor tem de ser recusada");
  }

  private static void jsonBonito() {
    String bruto = "{\"regras\":[{\"identificador\":\"R1\",\"ativa\":true,"
                   + "\"condicao\":{\"rotulos\":[\"CPF\"]},\"prioridade\":10}]}";
    String bonito = Json.formatar(bruto);
    certo(bonito.contains("\n"), "o texto formatado tem de ter quebras de linha");
    certo(bonito.contains("  \"regras\""), "e recuo");
    // Ida e volta: o que sai do editor tem de voltar igual ao que entrou.
    Map<String, Object> ida = Json.objeto(bruto);
    Map<String, Object> volta = Json.objeto(Json.bonito(ida));
    igual(Json.texto(Json.lista(ida, "regras").get(0), "identificador"),
          Json.texto(Json.lista(volta, "regras").get(0), "identificador"),
          "identificador sobrevive a ida e volta");
    certo(Json.logico(Json.lista(volta, "regras").get(0), "ativa", false),
          "o logico nao pode virar a STRING 'true' na volta");
    igual("10", Json.texto(Json.lista(volta, "regras").get(0), "prioridade"),
          "inteiro nao vira 10.0 na volta");
  }

  // ------------------------------------------------------------------ Html
  private static void escape() {
    String ataque = "<script>alert(document.cookie)</script>";
    String escapado = Html.t(ataque);
    certo(!escapado.contains("<script"), "a tag tem de ser neutralizada");
    certo(escapado.contains("&lt;script"), "e virar entidade");
    igual("&quot;&#39;&amp;", Html.t("\"'&"), "aspas, apostrofo e e-comercial");
    igual("&#47;", Html.t("/"), "barra escapada: fecha tag em navegador antigo");
    igual("31/08 14:37", Html.momento("2026-08-31T14:37:12+00:00"),
          "momento legivel");
    igual("1.234.567", Html.numero(1234567), "separador de milhar");
    igual("0", Html.numero(0), "zero");
    certo(Html.curto("abcdefghij", 5).endsWith("&hellip;"), "corte com reticencia");
  }

  // ----------------------------------------------------------------- Pagina
  private static Map<String, Object> incidenteDeTeste(String nomeArquivo) {
    Map<String, Object> i = new LinkedHashMap<>();
    i.put("identificador", "inc-1");
    i.put("momento", "2026-08-31T14:37:12+00:00");
    i.put("severidade", "ALTA");
    i.put("estado", "NOVO");
    i.put("canal", "DOWNLOAD");
    i.put("usuario", "maria.souza");
    i.put("nome_arquivo", nomeArquivo);
    i.put("regra_nome", "CPF nao sai");
    i.put("tamanho", 1234.0);
    List<Object> pedidas = new ArrayList<>();
    pedidas.add("MASCARAR");
    pedidas.add("NOTIFICAR_ADMIN");
    i.put("acoes", pedidas);
    List<Object> feitas = new ArrayList<>();
    feitas.add("NOTIFICAR_ADMIN");
    i.put("acoes_executadas", feitas);
    return i;
  }

  private static Tela telaDe(String aba) {
    Tela t = new Tela();
    t.aba = aba;
    t.usuario = "ana.fiscal";
    t.urlAcao = "/portal/acao";
    t.urlRecurso = "/portal/recurso?x=1";
    for (Pagina.Aba a : Pagina.ABAS) {
      t.urlsAbas.put(a.codigo, "/portal/render?aba=" + a.codigo);
    }
    return t;
  }

  private static void telaCompleta() {
    Map<String, Object> lista = new LinkedHashMap<>();
    List<Object> itens = new ArrayList<>();
    // Nome de arquivo com carga de XSS: quem sobe o arquivo escolhe o nome, e
    // sabe que um administrador vai abrir o incidente.
    itens.add(incidenteDeTeste("<img src=x onerror=alert(1)>.pdf"));
    lista.put("itens", itens);
    lista.put("total", 1.0);
    Map<String, Object> dados = new LinkedHashMap<>();
    dados.put("incidentes", lista);

    String html = Pagina.pagina(telaDe("incidentes"), dados);
    certo(!html.contains("<img src=x"),
          "o nome de arquivo do atacante NAO pode virar tag");
    certo(html.contains("&lt;img"), "tem de aparecer escapado");
    certo(html.contains("maria.souza"), "o usuario tem de aparecer");
    certo(html.contains("Incidentes"), "a aba tem de estar na tela");

    // Todas as abas montam sem explodir mesmo com dados ausentes -- e' o
    // estado real quando o servico acabou de subir.
    for (Pagina.Aba aba : Pagina.ABAS) {
      String saida = Pagina.pagina(telaDe(aba.codigo), new LinkedHashMap<>());
      certo(saida.contains("pmo-dlp"), "aba " + aba.codigo + " tem de montar");
      certo(saida.contains("Prote\u00e7\u00e3o de dados"),
            "aba " + aba.codigo + " tem de ter o titulo");
      certo(saida.contains(">" + Html.t(aba.rotulo) + "<"),
            "o rotulo da aba " + aba.codigo + " tem de aparecer na navegacao");
    }
  }

  private static void telaSemServico() {
    String html = Pagina.pagina(telaDe("painel"), new LinkedHashMap<>());
    certo(html.contains("nao respondeu"),
          "servico fora do ar tem de aparecer como tal, e nao como acervo limpo");
    certo(html.contains("exo-dlp"), "e a mensagem tem de dizer onde olhar");
  }

  private static void acoesPedidasContraExecutadas() {
    Map<String, Object> lista = new LinkedHashMap<>();
    List<Object> itens = new ArrayList<>();
    itens.add(incidenteDeTeste("ficha.pdf"));
    lista.put("itens", itens);
    Map<String, Object> dados = new LinkedHashMap<>();
    dados.put("incidentes", lista);
    String html = Pagina.pagina(telaDe("incidentes"), dados);
    certo(html.contains("pmo-nao-fez\">MASCARAR"),
          "acao pedida e NAO cumprida tem de aparecer riscada -- e' a coluna "
          + "que distingue politica de encenacao");
    certo(html.contains("pmo-fez\">NOTIFICAR_ADMIN"),
          "acao cumprida tem de aparecer como cumprida");
  }

  private static void evidenciaMascarada() {
    Map<String, Object> incidente = incidenteDeTeste("ficha.txt");
    List<Object> evidencia = new ArrayList<>();
    Map<String, Object> achado = new LinkedHashMap<>();
    achado.put("rotulo", "CPF");
    achado.put("severidade", "ALTA");
    achado.put("quantidade", 1.0);
    List<Object> amostras = new ArrayList<>();
    Map<String, Object> amostra = new LinkedHashMap<>();
    amostra.put("trecho", "...informou o numero [***.***.***-25] para deposito...");
    amostras.add(amostra);
    achado.put("amostras", amostras);
    evidencia.add(achado);
    incidente.put("evidencia", evidencia);
    Map<String, Object> dados = new LinkedHashMap<>();
    dados.put("incidente", incidente);

    String html = Pagina.pagina(telaDe("incidentes"), dados);
    certo(html.contains("***.***.***-25"),
          "o trecho mascarado tem de ser exibido: e' o que o analista usa");
    certo(html.contains("mascarada"),
          "a tela tem de dizer que a evidencia e' mascarada por desenho");
  }

  private static void acessoNegado() {
    String html = Pagina.acessoNegado("/platform/administrators");
    // A barra sai escapada, e e' assim que tem de ser: o nome do grupo vem de
    // configuracao e nao pode virar marcacao. A prova confere o texto ESCAPADO.
    certo(html.contains(Html.t("/platform/administrators")),
          "a recusa tem de dizer qual grupo e' exigido");
    certo(!html.contains("<nav"),
          "quem nao pode ver a tela nao ve nem a navegacao dela");
  }

  // -------------------------------------------------------------- aparelho
  private static void certo(boolean condicao, String mensagem) {
    asseveracoes++;
    if (!condicao) {
      falhas++;
      System.out.println("  FALHOU: " + mensagem);
    }
  }

  private static void igual(Object esperado, Object obtido, String mensagem) {
    asseveracoes++;
    if (esperado == null ? obtido != null : !esperado.equals(obtido)) {
      falhas++;
      System.out.println("  FALHOU: " + mensagem + "\n    esperado: " + esperado
                         + "\n    obtido:   " + obtido);
    }
  }
}
