package br.pmo.nuvem;

/**
 * Prova da normalizacao e validacao de caminho -- a defesa numero um contra o
 * servidor hostil. Nada aqui precisa de rede: e' pura logica.
 */
final class ProvaCaminho {

  private ProvaCaminho() {
  }

  static void rodar() {
    aceitaOQueEValido();
    recusaEscapeDeRaiz();
    recusaCorrupcao();
    juncaoEComponentes();
  }

  private static void aceitaOQueEValido() {
    Prova.secao("Caminho — aceita o que e' legitimo");

    Prova.certo("raiz aceita", CaminhoNuvem.raiz().ehRaiz());
    Prova.igual("caminho simples", "/arquivo.txt",
                CaminhoNuvem.de("/arquivo.txt").caminho());
    Prova.igual("caminho aninhado", "/pasta/sub/arquivo.txt",
                CaminhoNuvem.de("/pasta/sub/arquivo.txt").caminho());
    Prova.igual("nome do arquivo", "arquivo.txt",
                CaminhoNuvem.de("/pasta/arquivo.txt").nome());
    Prova.igual("pai do arquivo", "/pasta",
                CaminhoNuvem.de("/pasta/arquivo.txt").pai());
    Prova.igual("pai da raiz", "/", CaminhoNuvem.raiz().pai());
    Prova.igual("nome da raiz", "", CaminhoNuvem.raiz().nome());

    Prova.igual("juntar nome valido", "/pasta/novo.txt",
                CaminhoNuvem.de("/pasta").juntar("novo.txt").caminho());
    Prova.igual("juntar a partir da raiz", "/novo.txt",
                CaminhoNuvem.raiz().juntar("novo.txt").caminho());

    Prova.certo("caminho com acentos e' aceito (nome de arquivo real em PT)",
                CaminhoNuvem.de("/prestacao-de-contas/relatorio-final.pdf").caminho()
                    .contains("relatorio-final"));
  }

  private static void recusaEscapeDeRaiz() {
    Prova.secao("Caminho — RECUSA tentativa de escapar da raiz");

    String[] escapes = {
        "/../etc/passwd",
        "/pasta/../../etc",
        "/pasta/./arquivo",
        "/./arquivo",
        "/pasta/..",
        "/..",
        "..",
        "../etc",
    };
    for (String e : escapes) {
      boolean recusou = false;
      try {
        CaminhoNuvem.de(e);
      } catch (IllegalArgumentException ex) {
        recusou = true;
      }
      Prova.certo("recusa '" + e + "'", recusou);
    }

    boolean recusouNome = false;
    try {
      CaminhoNuvem.raiz().juntar("..");
    } catch (IllegalArgumentException ex) {
      recusouNome = true;
    }
    Prova.certo("juntar nome '..' recusa", recusouNome);
  }

  private static void recusaCorrupcao() {
    Prova.secao("Caminho — RECUSA corrupcao e separador de outro sistema");

    String[] corrompidos = {
        null, "", "  ", "sem-barra-inicial", "a/b/c",
        "/pasta//dupla", "/pasta/\\invertida", "/pasta/com\u0000nulo",
        "/pasta/com controle\u0007", "/pasta/com espaco",
        "/pasta/\u00a0espaco-invisivel", "//", "/",
    };
    // "/" e' valido (raiz); os demais devem recusar.
    for (String c : corrompidos) {
      if (c != null && c.equals("/")) {
        continue;
      }
      boolean recusou = false;
      try {
        CaminhoNuvem.de(c);
      } catch (IllegalArgumentException ex) {
        recusou = true;
      }
      Prova.certo("recusa '" + (c == null ? "null" : c.replace("\u0000", "\\0"))
          + "'", recusou);
    }

    boolean recusouBarra = false;
    try {
      CaminhoNuvem.raiz().juntar("a\\b");
    } catch (IllegalArgumentException ex) {
      recusouBarra = true;
    }
    Prova.certo("juntar nome com barra invertida recusa", recusouBarra);
  }

  private static void juncaoEComponentes() {
    Prova.secao("Caminho — componentes e imutabilidade");

    Prova.igual("ultimo componente", "b.txt", CaminhoNuvem.de("/a/b.txt").nome());
    Prova.igual("caminho pai", "/a", CaminhoNuvem.de("/a/b.txt").pai());
    Prova.igual("pai de subpasta", "/a", CaminhoNuvem.de("/a/b").pai());

    CaminhoNuvem original = CaminhoNuvem.de("/pasta");
    CaminhoNuvem juntado = original.juntar("x.txt");
    Prova.igual("original nao muda (imutavel)", "/pasta", original.caminho());
    Prova.igual("juntado tem o novo caminho", "/pasta/x.txt", juntado.caminho());

    Prova.igual("equals por caminho", CaminhoNuvem.de("/a"),
                CaminhoNuvem.de("/a"));
    Prova.certo("caminhos diferentes nao sao iguais",
                !CaminhoNuvem.de("/a").equals(CaminhoNuvem.de("/b")));
  }
}
