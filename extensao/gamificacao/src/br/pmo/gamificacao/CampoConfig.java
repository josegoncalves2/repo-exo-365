package br.pmo.gamificacao;

import java.util.Objects;

/**
 * Um campo de configuracao que o conector DECLARA precisar.
 *
 * <p>POR QUE DECLARADO, e nao chumbado no codigo: sem declaracao, a unica forma
 * de descobrir que um conector precisa de "token" e' ler o codigo-fonte dele. O
 * painel nao teria como desenhar o formulario, e a unica maneira de saber que
 * faltava credencial seria a chamada externa falhar em producao. Declarando, o
 * painel monta a tela sozinho e {@link Conector#estaConfigurado} responde antes
 * de qualquer chamada de rede.
 *
 * <p>POR QUE A MARCA {@code segredo} IMPORTA: e' ela que faz
 * {@link Configuracao#toString()} mascarar o valor. Um campo marcado como
 * segredo nunca sai em texto claro de nenhum {@code toString} desta extensao.
 */
public final class CampoConfig {

  private final String chave;

  private final String rotulo;

  private final boolean obrigatorio;

  private final boolean segredo;

  private CampoConfig(String chave, String rotulo, boolean obrigatorio, boolean segredo) {
    this.chave = Objects.requireNonNull(chave, "chave");
    this.rotulo = Objects.requireNonNull(rotulo, "rotulo");
    if (chave.isBlank()) {
      throw new IllegalArgumentException("campo sem chave");
    }
    this.obrigatorio = obrigatorio;
    this.segredo = segredo;
  }

  /** Campo visivel e obrigatorio: URL de API, identificador de projeto. */
  public static CampoConfig obrigatorio(String chave, String rotulo) {
    return new CampoConfig(chave, rotulo, true, false);
  }

  /** Campo visivel e dispensavel. */
  public static CampoConfig opcional(String chave, String rotulo) {
    return new CampoConfig(chave, rotulo, false, false);
  }

  /** Credencial obrigatoria: token, chave de API. Nunca aparece em log. */
  public static CampoConfig segredoObrigatorio(String chave, String rotulo) {
    return new CampoConfig(chave, rotulo, true, true);
  }

  /**
   * Credencial dispensavel: segredo de webhook.
   *
   * <p>E' dispensavel porque um conector serve para duas coisas independentes --
   * consultar a API do provedor e RECEBER webhook dele. Quem so' quer a primeira
   * nao deve ser obrigado a cadastrar segredo de webhook. Mas sem esse segredo
   * o webhook e' RECUSADO, nunca aceito sem conferir: ver
   * {@link Conector#receberWebhook}.
   */
  public static CampoConfig segredoOpcional(String chave, String rotulo) {
    return new CampoConfig(chave, rotulo, false, true);
  }

  public String chave() {
    return chave;
  }

  public String rotulo() {
    return rotulo;
  }

  public boolean obrigatorio() {
    return obrigatorio;
  }

  public boolean segredo() {
    return segredo;
  }

  @Override
  public String toString() {
    return "CampoConfig[" + chave + (obrigatorio ? " obrigatorio" : " opcional")
        + (segredo ? " segredo" : "") + "]";
  }
}
