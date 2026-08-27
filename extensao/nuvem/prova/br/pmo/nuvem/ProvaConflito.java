package br.pmo.nuvem;

/**
 * Prova da politica de conflito e do cofre de tokens.
 */
final class ProvaConflito {

  private ProvaConflito() {
  }

  static void rodar() {
    nenhumLadoMudou();
    umLadoMudou();
    doisLadosMudaram();
    etagAusente();
    cofreDeTokens();
  }

  private static void nenhumLadoMudou() {
    Prova.secao("Conflito — nenhum lado mudou: inalterado");

    Prova.igual("sem mudanca em nenhum lado", PoliticaConflito.Veredito.INALTERADO,
                PoliticaConflito.decidir("e1", "e1", false, false, null));
  }

  private static void umLadoMudou() {
    Prova.secao("Conflito — so' um lado mudou: vence quem mudou");

    Prova.igual("so' o remoto mudou", PoliticaConflito.Veredito.REMOTO_VENCE,
                PoliticaConflito.decidir("e1", "e2", false, true, null));
    Prova.igual("so' o local mudou", PoliticaConflito.Veredito.LOCAL_VENCE,
                PoliticaConflito.decidir("e1", "e1", true, false, null));
  }

  private static void doisLadosMudaram() {
    Prova.secao("Conflito — os dois mudaram: NUNCA sobrescrever em silencio");

    PoliticaConflito.Veredito v = PoliticaConflito.decidir("e1", "e2", true, true, null);
    Prova.igual("etag diferente + local mudou", PoliticaConflito.Veredito.CONFLITO, v);
    Prova.certo("sufixo de conflito e' estavel e nao vaza conteudo",
                PoliticaConflito.sufixoConflito(1724780000000L)
                    .matches("-conflito-\\d{8}-\\d{6}"));
  }

  private static void etagAusente() {
    Prova.secao("Conflito — etag ausente e' registrado, nao escondido");

    boolean[] confiavel = { true };
    PoliticaConflito.decidir(null, null, false, true, confiavel);
    Prova.certo("etag remoto nulo marca confiavelEtag=false", !confiavel[0]);

    boolean[] confiavel2 = { false };
    PoliticaConflito.decidir(null, "e9", false, true, confiavel2);
    Prova.certo("etag remoto presente marca confiavelEtag=true", confiavel2[0]);
  }

  private static void cofreDeTokens() {
    Prova.secao("Cofre — token nunca aparece em toString nem em digest");

    CofreTokens cofre = new CofreTokens();
    cofre.guardar("u", new OAuth2Cliente.Tokens("tok-acesso", "tok-refresh",
                                                System.currentTimeMillis() + 60_000));
    Prova.certo("acesso guardado", "tok-acesso".equals(cofre.acesso("u")));
    Prova.certo("refresh guardado", "tok-refresh".equals(cofre.refresh("u")));
    Prova.certo("nao expirado", !cofre.expirado("u", System.currentTimeMillis()));
    Prova.certo("expirado no futuro distante",
                cofre.expirado("u", System.currentTimeMillis() + 120_000));
    cofre.revogar("u");
    Prova.certo("revogado nao devolve acesso", cofre.acesso("u") == null);

    Prova.certo("digest e' curto e estavel",
                CofreTokens.digest("tok-secreto").length() == 8);
    Prova.certo("digest nao revela o token",
                !CofreTokens.digest("tok-secreto").equals("tok-secreto"));
    Prova.igual("toString do Tokens nao imprime valor",
                false, new OAuth2Cliente.Tokens("abc", "def", 0L).toString().contains("abc"));

    Prova.secao("Cofre — state anti-CSRF");
    CofreTokens.EstadoOAuth2 e = cofre.novoEstado();
    Prova.certo("state gerado nao e' vazio", e.getEstado().length() > 16);
    Prova.certo("state conferido e' consumido", cofre.conferirState(e.getEstado(),
                System.currentTimeMillis()));
    Prova.certo("reuso do mesmo state e' recusado",
                !cofre.conferirState(e.getEstado(), System.currentTimeMillis()));
    Prova.certo("state inexistente e' recusado",
                !cofre.conferirState("inexistente", System.currentTimeMillis()));
    Prova.certo("state nulo e' recusado",
                !cofre.conferirState(null, System.currentTimeMillis()));
  }
}
