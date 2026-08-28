package br.pmo.gamificacao;

import java.util.Objects;

/**
 * Resposta de TRES estados de toda operacao de conector.
 *
 * <p>POR QUE TRES, E NAO UM BOOLEANO. Um booleano so' sabe dizer "deu certo" ou
 * "nao deu", e junta no mesmo balde duas situacoes que exigem reacoes opostas:
 *
 * <ul>
 *   <li>{@code NAO_CONFIGURADO} -- ninguem cadastrou credencial ainda. Nao e'
 *       defeito, nao e' incidente, nao se alerta ninguem e nao se tenta de novo.
 *       A acao correta e' um convite na tela: "configure este conector".
 *   <li>{@code FALHOU} -- havia credencial e a conversa com o provedor deu
 *       errado: token revogado, provedor fora do ar, assinatura adulterada.
 *       ISSO e' incidente, merece log e merece nova tentativa.
 *   <li>{@code OK} -- conversou e o provedor confirmou.
 * </ul>
 *
 * <p>Com booleano, um conector recem-instalado -- estado absolutamente normal --
 * apareceria no painel como conector QUEBRADO, e treze conectores novos
 * produziriam treze falsos incidentes no primeiro boot. O operador aprenderia em
 * uma semana a ignorar o alarme, e o alarme deixaria de servir para o dia em que
 * um token for de fato revogado.
 *
 * <p>O {@link #codigo()} existe para a prova poder afirmar POR QUE algo falhou.
 * Uma prova que so' verifica "falhou" passa por acidente quando o codigo quebra
 * por um motivo diferente do que ela pretendia exercitar.
 */
public final class Resultado {

  /** Os tres desfechos possiveis. Nunca colapsar em booleano. */
  public enum Estado {
    NAO_CONFIGURADO, OK, FALHOU
  }

  private final Estado estado;

  private final String codigo;

  private final String detalhe;

  private Resultado(Estado estado, String codigo, String detalhe) {
    this.estado = Objects.requireNonNull(estado, "estado");
    this.codigo = Objects.requireNonNull(codigo, "codigo");
    this.detalhe = detalhe == null ? "" : detalhe;
  }

  /** Falta credencial ou campo obrigatorio. Nao e' erro; e' tarefa pendente. */
  public static Resultado naoConfigurado(String detalhe) {
    return new Resultado(Estado.NAO_CONFIGURADO, "nao.configurado", detalhe);
  }

  public static Resultado ok(String detalhe) {
    return new Resultado(Estado.OK, "ok", detalhe);
  }

  /**
   * Falha efetiva. O {@code codigo} e' vocabulario fechado e estavel -- por
   * exemplo {@code http.401}, {@code assinatura.invalida},
   * {@code redirecionamento.cruzado} -- porque e' por ele que a prova e o painel
   * distinguem um caso do outro. O {@code detalhe} e' texto livre para humano.
   *
   * <p>O detalhe NUNCA deve conter credencial: quem o preenche e' o codigo desta
   * extensao, e nenhuma chamada aqui passa {@link Segredo#revelar()} adiante.
   */
  public static Resultado falhou(String codigo, String detalhe) {
    if (codigo == null || codigo.isBlank()) {
      throw new IllegalArgumentException("falha sem codigo nao e' diagnosticavel");
    }
    return new Resultado(Estado.FALHOU, codigo, detalhe);
  }

  public Estado estado() {
    return estado;
  }

  public String codigo() {
    return codigo;
  }

  public String detalhe() {
    return detalhe;
  }

  public boolean ok() {
    return estado == Estado.OK;
  }

  public boolean naoConfigurado() {
    return estado == Estado.NAO_CONFIGURADO;
  }

  public boolean falhou() {
    return estado == Estado.FALHOU;
  }

  @Override
  public String toString() {
    return "Resultado[" + estado + " " + codigo + (detalhe.isEmpty() ? "" : " " + detalhe) + "]";
  }
}
