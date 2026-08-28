package br.pmo.painel;

import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Acesso aos rotulos da tela, que moram em
 * {@code WEB-INF/classes/locale/portlet/painel/Conformidade_*.properties}.
 *
 * <p><b>POR QUE UMA CLASSE E NAO {@code bundle.getString} direto.</b> Duas
 * razoes, e a segunda e' a que paga o custo:
 *
 * <ol>
 *   <li>{@link ResourceBundle#getString(String)} lanca
 *       {@link MissingResourceException} quando falta a chave. Uma traducao
 *       esquecida derrubaria a tela INTEIRA de administracao em vez de estragar
 *       uma linha. Trocar a tela do relatorio de conformidade por uma pagina de
 *       erro, porque falta uma palavra, e' o remedio pior que a doenca;</li>
 *   <li>esta classe e' JDK puro -- nao conhece {@code javax.portlet}. E' o que
 *       permite montar a tela inteira fora do portal e PROVA-LA no host, com
 *       {@code javac} e {@code java}, inclusive provando que toda chave que a
 *       tela usa existe mesmo no arquivo de idioma.</li>
 * </ol>
 *
 * <p><b>CHAVE FALTANDO FICA BARULHENTA, NAO SILENCIOSA.</b> {@link #de(String)}
 * devolve {@code !chave!} quando nao acha. Nao devolve a chave nua (que parece
 * um rotulo tecnico e passa despercebido em revisao) nem cadeia vazia (que some
 * da tela e vira um campo sem titulo que ninguem nota). Os pontos de exclamacao
 * fazem a falta saltar aos olhos de quem abre a tela E permitem que a prova
 * detecte a ausencia sem depender de excecao.
 *
 * <p>Imutavel. {@link ResourceBundle} e' seguro para leitura concorrente.
 */
public final class Rotulos {

  /**
   * Nome base do arquivo de idioma. Declarado aqui porque a prova precisa
   * carregar EXATAMENTE o mesmo arquivo que o portlet carrega -- se a prova
   * olhasse outro arquivo, ela passaria enquanto a tela quebra.
   */
  public static final String NOME_BASE = "locale.portlet.painel.Conformidade";

  private final ResourceBundle pacote;

  /**
   * @param pacote o pacote de idioma ja' resolvido para a lingua do usuario;
   *               nulo e' aceito e faz toda chave sair como {@code !chave!}, que
   *               e' o comportamento certo para "o portal nao conseguiu carregar
   *               o idioma": a tela ainda abre e o defeito fica visivel
   */
  public Rotulos(ResourceBundle pacote) {
    this.pacote = pacote;
  }

  /**
   * O texto do rotulo.
   *
   * @param chave chave do arquivo de idioma
   * @return o texto traduzido, ou {@code !chave!} se a chave nao existe. Nunca
   *         nulo, nunca lanca.
   */
  public String de(String chave) {
    String valor = buscar(chave);
    return valor == null ? "!" + (chave == null ? "" : chave) + "!" : valor;
  }

  /**
   * O valor cru, ou {@code null} quando a chave nao existe ou esta' vazia.
   *
   * <p>E' este metodo -- e nao o formato {@code !chave!} -- que define
   * "faltando". {@link #tem(String)} e {@link #formatar(String, Object...)} se
   * apoiam nele para nao confundir uma chave ausente com um rotulo que por
   * acaso comece com ponto de exclamacao.
   */
  private String buscar(String chave) {
    if (chave == null || chave.isEmpty() || pacote == null) {
      return null;
    }
    try {
      String valor = pacote.getString(chave);
      // Chave presente porem vazia e' o mesmo defeito de chave ausente: um
      // rotulo em branco na tela. Trata igual, para nao existir um jeito
      // silencioso de esconder a falta.
      return valor == null || valor.isEmpty() ? null : valor;
    } catch (MissingResourceException e) {
      return null;
    }
  }

  /**
   * O texto do rotulo com substituicao posicional de {@code {0}}, {@code {1}}...
   *
   * <p><b>POR QUE NAO {@code MessageFormat}.</b> {@code MessageFormat} trata
   * apostrofo como caractere de escape: em portugues, um rotulo como
   * {@code "ate' {0} caracteres"} sairia com o {@code {0}} literal na tela.
   * Substituicao posicional simples nao tem essa armadilha, e nao ha' aqui
   * nenhum caso de plural ou de formato de numero que justificasse o resto do
   * {@code MessageFormat}.
   *
   * @param chave chave do arquivo de idioma
   * @param args  valores; nulo em qualquer posicao vira cadeia vazia
   * @return o texto com os valores aplicados; se a chave falta, o
   *         {@code !chave!} de {@link #de(String)} -- e ai' os argumentos NAO
   *         sao anexados, para o marcador de falta continuar reconhecivel
   */
  public String formatar(String chave, Object... args) {
    String modelo = buscar(chave);
    if (modelo == null) {
      return de(chave);
    }
    if (args == null || args.length == 0) {
      return modelo;
    }
    String texto = modelo;
    for (int i = 0; i < args.length; i++) {
      String valor = args[i] == null ? "" : String.valueOf(args[i]);
      texto = texto.replace("{" + i + "}", valor);
    }
    return texto;
  }

  /**
   * Se a chave existe e tem conteudo. Existe para a prova poder conferir a
   * cobertura do arquivo de idioma sem tratar excecao.
   */
  public boolean tem(String chave) {
    return buscar(chave) != null;
  }
}
