package br.pmo.gamificacao;

/**
 * Envelope de credencial cuja unica saida em texto claro e' {@link #revelar()}.
 *
 * <p>POR QUE ESTE TIPO EXISTE. Token guardado em {@code String} vaza pelo
 * caminho mais banal que existe: alguem escreve {@code log.info("config=" +
 * config)} para depurar, o {@code toString} do mapa imprime o token, e a
 * credencial fica no disco do servidor, no rsyslog e no backup. Envolvendo em um
 * tipo cujo {@code toString} nao tem o valor, esse acidente deixa de ser
 * possivel: o log passa a mostrar {@code Segredo(***)}.
 *
 * <p>Sobra um unico caminho para o texto claro, {@link #revelar()}, que e' uma
 * palavra rara e portanto AUDITAVEL com um grep. Cada uso dela e' um lugar onde
 * a credencial e' de fato necessaria -- montar o cabecalho HTTP, calcular o
 * HMAC -- e nenhum outro.
 */
public final class Segredo {

  private static final Segredo AUSENTE = new Segredo(null);

  private final String valor;

  private Segredo(String valor) {
    this.valor = valor;
  }

  /** Envelopa um valor; texto em branco e' tratado como ausencia. */
  public static Segredo de(String valor) {
    if (valor == null || valor.isBlank()) {
      return AUSENTE;
    }
    return new Segredo(valor);
  }

  /** O segredo que nao foi cadastrado. */
  public static Segredo ausente() {
    return AUSENTE;
  }

  /** {@code true} quando nao ha' credencial cadastrada. */
  public boolean vazio() {
    return valor == null;
  }

  public boolean presente() {
    return valor != null;
  }

  /**
   * Devolve o texto claro. Unico caminho de saida, de proposito.
   *
   * @throws IllegalStateException se o segredo esta' ausente -- devolver ""
   *         faria o chamador montar um cabecalho {@code Authorization: Bearer }
   *         vazio e receber 401, um erro de rede mascarando um erro de
   *         configuracao. Ver {@link Resultado} sobre por que os dois nao podem
   *         se confundir.
   */
  public String revelar() {
    if (valor == null) {
      throw new IllegalStateException("segredo ausente: verifique estaConfigurado() antes de revelar()");
    }
    return valor;
  }

  /** Nunca inclui o valor. Esta e' a razao de ser da classe. */
  @Override
  public String toString() {
    return valor == null ? "Segredo(ausente)" : "Segredo(***)";
  }

  /**
   * Comparacao em tempo constante, para o caso de alguem comparar segredo com
   * segredo. Delega em {@link Bytes#iguaisTempoConstante}.
   */
  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Segredo)) {
      return false;
    }
    Segredo outro = (Segredo) o;
    if (valor == null || outro.valor == null) {
      return valor == null && outro.valor == null;
    }
    return Bytes.iguaisTempoConstante(
        valor.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        outro.valor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * Constante de proposito: hash que varia com o segredo permitiria descobrir o
   * valor por colisao. Todos os segredos caem no mesmo balde, o que e' correto e
   * irrelevante -- nao se usa {@code Segredo} como chave de mapa.
   */
  @Override
  public int hashCode() {
    return 0;
  }
}
