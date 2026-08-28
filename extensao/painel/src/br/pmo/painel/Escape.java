package br.pmo.painel;

/**
 * Neutralizacao de texto que vai para dentro de HTML.
 *
 * <p><b>POR QUE ISTO E' A PRIMEIRA CLASSE DO PACOTE.</b> A tela do painel recebe
 * texto do administrador (a caixa "Analisar texto") e devolve HTML. Ela tambem
 * imprime dados que vieram do motor -- rotulo de regra, motivo de varredura
 * incompleta, motivo cru nao reconhecido -- e ESSES motivos carregam pedaco de
 * nome de arquivo, que por sua vez veio de quem fez o upload. Ou seja: ha' pelo
 * menos dois caminhos por onde texto de terceiro chega ao HTML.
 *
 * <p>Injecao de script numa tela de ADMINISTRACAO nao e' defeito de cosmetica:
 * o navegador que executa o script e' o do administrador, com a sessao dele. Um
 * {@code <script>} que passe daqui vira criacao de usuario, troca de permissao
 * ou exfiltracao do relatorio inteiro -- escalonamento de privilegio completo,
 * disparado por quem so' precisava conseguir anexar um arquivo com nome
 * malicioso.
 *
 * <p><b>POR QUE CINCO CARACTERES E NAO TRES.</b> {@code & < >} fecham o caso do
 * texto solto entre tags. Faltam as duas aspas: valor de atributo nao citado ou
 * citado com aspa simples escapa do atributo sem precisar de {@code <}, e ai'
 * basta um {@code onerror=} para executar. Como a mesma funcao e' usada nos dois
 * contextos, ela cobre os dois.
 *
 * <p><b>A ORDEM DO {@code &} E' OBRIGATORIA.</b> Trocar {@code &} depois de
 * {@code <} transformaria o {@code &lt;} recem-criado em {@code &amp;lt;}, e o
 * texto sairia visivelmente errado na tela -- o tipo de bug que se "conserta"
 * removendo o escape.
 *
 * <p>Sem estado, sem I/O.
 */
public final class Escape {

  private Escape() {
  }

  /**
   * Neutraliza {@code & < > " '} para uso em texto ou em valor de atributo.
   *
   * @param bruto texto de qualquer origem; nulo e' aceito
   * @return o texto seguro. <b>NUNCA devolve nulo</b>, inclusive para entrada
   *         nula: entrada nula vira cadeia vazia. E' decisao consciente e nao
   *         perde informacao util -- quem chama esta montando HTML e "nada" e
   *         "cadeia vazia" imprimem igual. Devolver nulo aqui plantaria um
   *         {@code NullPointerException} no meio da montagem da pagina, isto e',
   *         trocaria um campo vazio por uma tela em branco.
   */
  public static String html(String bruto) {
    if (bruto == null || bruto.isEmpty()) {
      return "";
    }
    // Passa uma vez so'. O caso comum -- texto sem nenhum caractere de risco --
    // sai sem alocar StringBuilder nenhum.
    if (!precisaEscapar(bruto)) {
      return bruto;
    }
    StringBuilder sb = new StringBuilder(bruto.length() + 16);
    for (int i = 0; i < bruto.length(); i++) {
      char c = bruto.charAt(i);
      switch (c) {
        case '&':
          sb.append("&amp;");
          break;
        case '<':
          sb.append("&lt;");
          break;
        case '>':
          sb.append("&gt;");
          break;
        case '"':
          sb.append("&quot;");
          break;
        case '\'':
          // &#39; e nao &apos;: &apos; nao existe em HTML 4 e ha' navegador
          // antigo que imprime o literal. A forma numerica funciona em todos.
          sb.append("&#39;");
          break;
        default:
          sb.append(c);
          break;
      }
    }
    return sb.toString();
  }

  private static boolean precisaEscapar(String texto) {
    for (int i = 0; i < texto.length(); i++) {
      char c = texto.charAt(i);
      if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') {
        return true;
      }
    }
    return false;
  }
}
