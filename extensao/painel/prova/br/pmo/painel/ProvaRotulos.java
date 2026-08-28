package br.pmo.painel;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Provas de {@link Rotulos}: falta de traducao nao pode derrubar a tela, e
 * tambem nao pode passar despercebida.
 */
public final class ProvaRotulos {

  private ProvaRotulos() {
  }

  public static void rodar() {
    Prova.secao("Rotulos — chave faltando fica barulhenta, nao derruba");
    Rotulos pt = new Rotulos(ResourceBundle.getBundle(Rotulos.NOME_BASE,
                                                      new Locale("pt", "BR")));
    Prova.igual("chave existente devolve o texto traduzido",
                "Conformidade DLP", pt.de(PainelHtml.CH_TITULO));
    Prova.certo("chave existente e' reconhecida por tem()", pt.tem(PainelHtml.CH_TITULO));

    // getString lancaria MissingResourceException e trocaria a tela inteira do
    // relatorio por uma pagina de erro, por causa de UMA palavra faltando.
    Prova.igual("chave ausente vira marcador visivel, sem excecao",
                "!painel.chave.que.nao.existe!", pt.de("painel.chave.que.nao.existe"));
    Prova.certo("e tem() a reconhece como ausente",
                !pt.tem("painel.chave.que.nao.existe"));
    Prova.igual("chave nula nao explode", "!!", pt.de(null));

    Prova.secao("Rotulos — sem pacote de idioma a tela ainda abre");
    Rotulos semPacote = new Rotulos(null);
    Prova.igual("toda chave vira marcador", "!painel.titulo!",
                semPacote.de(PainelHtml.CH_TITULO));
    Prova.certo("e nada lanca excecao", semPacote.de("qualquer.coisa") != null);

    Prova.secao("Rotulos — substituicao posicional");
    Prova.certo("o {0} do total e' substituido, nao impresso literal",
                pt.formatar(PainelHtml.CH_TOTAL, 7).contains("7")
                && !pt.formatar(PainelHtml.CH_TOTAL, 7).contains("{0}"));
    Prova.certo("dois argumentos sao substituidos",
                !pt.formatar(PainelHtml.CH_ANALISE_METRICA, 10, 3L).contains("{"));
    Prova.igual("argumento nulo vira vazio, nao a palavra null",
                false, pt.formatar(PainelHtml.CH_TOTAL, (Object) null).contains("null"));
    Prova.certo("chave ausente com argumentos continua sendo o marcador limpo",
                "!nao.existe!".equals(pt.formatar("nao.existe", 1, 2)));

    // MessageFormat trataria o apostrofo do portugues como escape e imprimiria
    // o {0} literal. A substituicao simples nao tem essa armadilha.
    Prova.secao("Rotulos — apostrofo nao engole o argumento");
    Rotulos comApostrofo = new Rotulos(new java.util.ListResourceBundle() {
      @Override
      protected Object[][] getContents() {
        return new Object[][] {{"k", "n'ao {0} itens"}};
      }
    });
    Prova.igual("o argumento sobrevive ao apostrofo", "n'ao 5 itens",
                comApostrofo.formatar("k", 5));
  }
}
