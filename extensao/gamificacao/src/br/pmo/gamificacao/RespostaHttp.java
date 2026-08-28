package br.pmo.gamificacao;

/**
 * Resposta HTTP reduzida ao que os conectores precisam: status e corpo.
 *
 * <p>Nao guarda cabecalhos de resposta de proposito -- eles costumam trazer
 * cookie de sessao e token de renovacao, e nada aqui precisa deles. O que nao se
 * guarda nao vaza em log.
 */
public final class RespostaHttp {

  private final int status;

  private final String corpo;

  public RespostaHttp(int status, String corpo) {
    this.status = status;
    this.corpo = corpo == null ? "" : corpo;
  }

  public int status() {
    return status;
  }

  public String corpo() {
    return corpo;
  }

  public boolean sucesso() {
    return status >= 200 && status < 300;
  }

  /** Nao imprime o corpo: resposta de API traz dado de usuario. */
  @Override
  public String toString() {
    return "RespostaHttp[status=" + status + ", corpo=" + corpo.length() + " chars]";
  }
}
