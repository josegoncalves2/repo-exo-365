package br.pmo.painel;

/**
 * O texto que o administrador colou na caixa, ja' medido contra o teto do
 * painel.
 *
 * <p><b>POR QUE O PAINEL TEM TETO PROPRIO SE O MOTOR JA' TEM UM.</b> Sao tetos
 * de coisas diferentes, e o do motor nao cobre o do painel:
 *
 * <ul>
 *   <li>o teto do motor ({@code Varredura.TETO_CARACTERES_PADRAO}, 2.000.000)
 *       protege a VARREDURA. Ele so' age depois que a {@code String} inteira ja'
 *       existe em memoria;</li>
 *   <li>o teto daqui protege o PORTAL antes disso. Um POST de 500 MB numa tela
 *       de administracao ja' custou a memoria toda no momento em que o
 *       contentor montou o parametro -- muito antes de o motor opinar. E o
 *       ataque nao precisa nem ser grande: basta ser repetido.</li>
 * </ul>
 *
 * <p><b>ESTOURAR O TETO NAO PRODUZ "TEXTO LIMPO".</b> E' a mesma regra que o
 * motor aplica ao proprio teto, e pelo mesmo motivo: se o corte virasse
 * silenciosamente uma varredura completa de um pedaco, o jeito trivial de passar
 * dado sensivel pela tela seria empurra-lo para depois do corte. Por isso
 * {@link #isTruncado()} vem acompanhado de {@link #getMotivo()} JA' ESCRITO em
 * portugues, pronto para
 * {@code Varredura.varrerParcial(String, String)} -- que e' o metodo que marca o
 * laudo como incompleto.
 *
 * <p>Imutavel.
 */
public final class TextoSubmetido {

  private final String texto;

  private final boolean truncado;

  private final String motivo;

  private final int tamanhoOriginal;

  private TextoSubmetido(String texto, boolean truncado, String motivo, int tamanhoOriginal) {
    this.texto = texto;
    this.truncado = truncado;
    this.motivo = motivo;
    this.tamanhoOriginal = tamanhoOriginal;
  }

  /**
   * Mede o texto contra o teto e devolve o que deve ser varrido.
   *
   * @param bruto o parametro como veio do formulario; nulo ou vazio e' aceito e
   *              devolve um submetido vazio e NAO truncado -- caixa em branco
   *              nao e' texto cortado, e' ausencia de texto
   * @param teto  maximo de caracteres aceito pelo painel
   * @return sempre um objeto, nunca nulo
   * @throws IllegalArgumentException se {@code teto} nao for positivo. Teto zero
   *         ou negativo so' chega aqui por configuracao errada, e o efeito
   *         silencioso seria truncar TUDO para nada -- uma tela que responde
   *         "nao varrido" a qualquer entrada, sem ninguem entender por que.
   *         Falhar alto na partida do portlet e' o unico jeito de isso ser
   *         corrigido.
   */
  public static TextoSubmetido de(String bruto, int teto) {
    if (teto <= 0) {
      throw new IllegalArgumentException(
          "teto de caracteres do painel tem de ser positivo, veio " + teto);
    }
    if (bruto == null || bruto.isEmpty()) {
      return new TextoSubmetido("", false, null, 0);
    }
    if (bruto.length() <= teto) {
      return new TextoSubmetido(bruto, false, null, bruto.length());
    }
    String motivo = "texto colado tem " + bruto.length()
                    + " caracteres, acima do teto de " + teto
                    + " caracteres desta tela; analisados os primeiros " + teto;
    return new TextoSubmetido(bruto.substring(0, teto), true, motivo, bruto.length());
  }

  /** O texto que deve ser varrido: o original, ou o prefixo que coube. */
  public String getTexto() {
    return texto;
  }

  /** Verdadeiro quando o original nao coube inteiro. */
  public boolean isTruncado() {
    return truncado;
  }

  /**
   * Por que ficou incompleto, em portugues, pronto para o relatorio e para a
   * tela. Nulo quando {@link #isTruncado()} e' falso -- o par
   * (truncado=false, motivo=null) e' o unico estado de sucesso, e nao ha' como
   * confundi-lo com um corte.
   */
  public String getMotivo() {
    return motivo;
  }

  /** Quantos caracteres o administrador enviou, antes do corte. */
  public int getTamanhoOriginal() {
    return tamanhoOriginal;
  }

  /** Verdadeiro quando nao ha' nada para analisar. */
  public boolean isVazio() {
    return texto.isEmpty();
  }
}
