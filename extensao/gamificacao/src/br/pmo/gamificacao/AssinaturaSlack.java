package br.pmo.gamificacao;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Esquema de assinatura do Slack, versao {@code v0}.
 *
 * <p>A mensagem assinada NAO e' o corpo: e'
 * {@code "v0:" + carimbo + ":" + corpo}. O carimbo vai junto no cabecalho
 * {@code X-Slack-Request-Timestamp}, e e' isso que da' ao esquema uma defesa que
 * o HMAC simples do Github nao tem.
 *
 * <p>POR QUE A JANELA DE TEMPO E' PARTE DA CONFERENCIA, e nao um extra. Como o
 * carimbo esta' DENTRO do que foi assinado, ele nao pode ser alterado por quem
 * capturou a requisicao -- mexer no carimbo invalida o HMAC. Entao recusar
 * carimbo velho recusa REPETICAO: uma requisicao legitima gravada por um
 * intermediario deixa de valer depois de cinco minutos. Sem essa conferencia o
 * HMAC continuaria batendo para sempre e a gravacao poderia ser reenviada mil
 * vezes, mil vezes pontuando.
 *
 * <p>A janela vale para os DOIS lados. Carimbo no futuro tambem e' recusado:
 * aceitar futuro permitiria forjar um evento com validade longa caso o segredo
 * vazasse por um instante.
 *
 * <p>O relogio e' injetavel para a prova poder exercitar carimbo velho e carimbo
 * futuro sem esperar cinco minutos nem mexer no relogio da maquina.
 */
public final class AssinaturaSlack implements Assinatura {

  /** Cinco minutos, a mesma janela que o Slack recomenda. */
  public static final long JANELA_SEGUNDOS = 300L;

  private static final String CABECALHO_ASSINATURA = "X-Slack-Signature";

  private static final String CABECALHO_CARIMBO = "X-Slack-Request-Timestamp";

  private static final String VERSAO = "v0";

  private final String chaveSegredo;

  private final LongSupplier relogioEpochSegundos;

  public AssinaturaSlack(String chaveSegredo) {
    this(chaveSegredo, () -> System.currentTimeMillis() / 1000L);
  }

  public AssinaturaSlack(String chaveSegredo, LongSupplier relogioEpochSegundos) {
    this.chaveSegredo = chaveSegredo;
    this.relogioEpochSegundos = relogioEpochSegundos;
  }

  @Override
  public String chaveSegredo() {
    return chaveSegredo;
  }

  @Override
  public String descricao() {
    return "Slack v0 (HMAC-SHA256 sobre v0:carimbo:corpo, janela de "
        + JANELA_SEGUNDOS + "s)";
  }

  @Override
  public Resultado conferir(Configuracao config, EventoEntrada evento) {
    Segredo segredo = config.segredo(chaveSegredo);
    if (segredo.vazio()) {
      return Resultado.naoConfigurado(
          "sem segredo de webhook em '" + chaveSegredo + "': webhook nao pode ser conferido");
    }
    String assinatura = evento.cabecalho(CABECALHO_ASSINATURA);
    String carimbo = evento.cabecalho(CABECALHO_CARIMBO);
    if (assinatura == null || assinatura.isBlank()) {
      return Resultado.falhou("assinatura.ausente", CABECALHO_ASSINATURA + " nao veio");
    }
    if (carimbo == null || carimbo.isBlank()) {
      return Resultado.falhou("carimbo.ausente", CABECALHO_CARIMBO + " nao veio");
    }
    long enviado;
    try {
      enviado = Long.parseLong(carimbo.trim());
    } catch (NumberFormatException e) {
      return Resultado.falhou("carimbo.malformado", "carimbo nao e' numero");
    }
    long agora = relogioEpochSegundos.getAsLong();
    long diferenca = Math.abs(agora - enviado);
    if (diferenca > JANELA_SEGUNDOS) {
      return Resultado.falhou("carimbo.fora.da.janela",
          "carimbo distante " + diferenca + "s do relogio local");
    }
    String base = VERSAO + ":" + carimbo.trim() + ":" + evento.corpoTexto();
    String calculada = VERSAO + "="
        + Hmac.hex("HmacSHA256", segredo, base.getBytes(StandardCharsets.UTF_8));
    if (!Bytes.iguaisTempoConstante(calculada, assinatura.trim().toLowerCase(Locale.ROOT))) {
      return Resultado.falhou("assinatura.invalida", "HMAC nao confere");
    }
    return Resultado.ok("assinatura conferida dentro da janela");
  }
}
