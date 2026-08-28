package br.pmo.gamificacao;

/**
 * A requisicao ia sair para um host que NAO e' o configurado para este conector.
 *
 * <p>POR QUE ISTO E' EXCECAO E NAO UM AVISO. A requisicao carrega a credencial
 * do provedor no cabecalho. Se o destino mudou -- por redirecionamento, por
 * caminho montado errado, por configuracao adulterada -- entao o token do
 * provedor A esta' prestes a ser entregue ao servidor B. Isso e' vazamento de
 * credencial, e nao existe resposta correta a nao ser NAO ENVIAR. Excecao
 * garante que nao ha' caminho de codigo que continue por engano.
 */
public class DestinoInvalidoException extends Exception {

  private static final long serialVersionUID = 1L;

  private final String codigo;

  public DestinoInvalidoException(String codigo, String mensagem) {
    super(mensagem);
    this.codigo = codigo;
  }

  /** Codigo estavel para {@link Resultado#falhou}. */
  public String codigo() {
    return codigo;
  }
}
