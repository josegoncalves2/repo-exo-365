package br.pmo.painel;

/**
 * Provas de {@link Escape}. E' a classe que o fiscal ataca primeiro, entao as
 * asseveracoes sao de ATAQUE, nao de formato: cada uma monta um vetor que
 * funcionaria numa tela de administracao e confere que ele nao sobrevive.
 */
public final class ProvaEscape {

  private ProvaEscape() {
  }

  public static void rodar() {
    Prova.secao("Escape — os cinco caracteres");
    Prova.igual("& vira &amp;", "&amp;", Escape.html("&"));
    Prova.igual("< vira &lt;", "&lt;", Escape.html("<"));
    Prova.igual("> vira &gt;", "&gt;", Escape.html(">"));
    Prova.igual("aspa dupla vira &quot;", "&quot;", Escape.html("\""));
    Prova.igual("aspa simples vira &#39;", "&#39;", Escape.html("'"));

    Prova.secao("Escape — ordem do & (o erro classico)");
    // Se o & fosse trocado DEPOIS do <, o resultado seria &amp;lt; e a tela
    // mostraria "&lt;" literal ao usuario. Esta asseveracao trava essa regressao.
    Prova.igual("< nao vira &amp;lt; (nao ha' escape duplo)", "&lt;", Escape.html("<"));
    Prova.igual("texto ja' escapado e' escapado de novo, sem perder o original",
                "&amp;lt;", Escape.html("&lt;"));

    Prova.secao("Escape — vetores de XSS reais");
    String script = "<script>alert(document.cookie)</script>";
    String saidaScript = Escape.html(script);
    Prova.certo("nenhum '<' sobra de um <script>", saidaScript.indexOf('<') < 0);
    Prova.certo("nenhum '>' sobra de um <script>", saidaScript.indexOf('>') < 0);
    Prova.certo("a palavra script continua legivel (nao se apagou conteudo)",
                saidaScript.contains("script"));

    String imgOnerror = "<img src=x onerror=alert(1)>";
    Prova.certo("<img onerror> perde os delimitadores",
                Escape.html(imgOnerror).indexOf('<') < 0);

    // O caso que os tres caracteres classicos NAO pegam: fuga de atributo.
    // Sem escapar aspas, isto fecha o atributo e injeta um manipulador de
    // evento sem precisar de nenhum '<'.
    String fugaDeAtributo = "x\" onmouseover=\"alert(1)";
    String saidaFuga = Escape.html(fugaDeAtributo);
    Prova.certo("fuga por aspa dupla e' neutralizada", saidaFuga.indexOf('"') < 0);
    String fugaSimples = "x' onmouseover='alert(1)";
    Prova.certo("fuga por aspa simples e' neutralizada",
                Escape.html(fugaSimples).indexOf('\'') < 0);

    String javascriptUrl = "javascript:alert('x')";
    Prova.certo("aspa dentro de URL de javascript e' neutralizada",
                Escape.html(javascriptUrl).indexOf('\'') < 0);

    Prova.secao("Escape — contrato de nulo e vazio");
    Prova.igual("nulo vira cadeia vazia, nunca nulo", "", Escape.html(null));
    Prova.igual("vazio continua vazio", "", Escape.html(""));
    Prova.certo("nunca devolve nulo", Escape.html(null) != null);

    Prova.secao("Escape — texto inocente atravessa intacto");
    // Se o escape estragasse texto normal, o operador veria entidades na tela e
    // a reacao seria remover o escape. Preservar o caso comum protege o escape.
    String limpo = "Conformidade DLP — 12 itens, 3,50% do acervo";
    Prova.igual("texto sem caractere de risco sai identico", limpo, Escape.html(limpo));
    Prova.igual("acentuacao nao e' alterada", "Não varrido", Escape.html("Não varrido"));
  }
}
