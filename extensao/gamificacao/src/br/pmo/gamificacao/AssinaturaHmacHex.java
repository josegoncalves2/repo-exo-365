package br.pmo.gamificacao;

import java.util.Locale;

/**
 * Esquema "HMAC-SHA256 hexadecimal em cabecalho", usado por Github e Notion.
 *
 * <p>O provedor calcula {@code HMAC-SHA256(segredo, corpo_cru)} e manda o
 * resultado em hexadecimal, normalmente com um prefixo indicando o algoritmo
 * ({@code sha256=} no Github). Aqui se recalcula e se compara em tempo
 * constante.
 *
 * <p>POR QUE O PREFIXO E' CONFERIDO E NAO SO' REMOVIDO. Se o codigo apenas
 * cortasse tudo antes do "=", um atacante mandaria {@code sha1=...} e o servidor
 * compararia um HMAC-SHA1 forjado contra o SHA-256 calculado -- que nunca bate,
 * tudo bem -- mas tambem aceitaria {@code =} sozinho, ou prefixo vazio, abrindo
 * caminho para confusao de algoritmo. Exigindo o prefixo exato, so' existe um
 * algoritmo possivel.
 *
 * <p>POR QUE NAO HA' JANELA DE TEMPO AQUI. Este esquema nao inclui carimbo de
 * hora na mensagem assinada, entao uma requisicao legitima capturada por um
 * intermediario pode ser reenviada mais tarde e continuara valida (repeticao).
 * A defesa possivel e' exigir HTTPS ate' a borda -- que este codigo nao
 * controla -- e deduplicar por identificador de entrega. Quem quiser janela de
 * tempo usa {@link AssinaturaSlack}, que assina o carimbo junto.
 */
public final class AssinaturaHmacHex implements Assinatura {

  private final String chaveSegredo;

  private final String cabecalho;

  private final String prefixo;

  /**
   * @param chaveSegredo chave de configuracao com o segredo do webhook
   * @param cabecalho nome do cabecalho que traz a assinatura
   * @param prefixo prefixo exigido, por exemplo {@code sha256=}; use "" se o
   *        provedor manda o hexadecimal puro
   */
  public AssinaturaHmacHex(String chaveSegredo, String cabecalho, String prefixo) {
    this.chaveSegredo = chaveSegredo;
    this.cabecalho = cabecalho;
    this.prefixo = prefixo == null ? "" : prefixo;
  }

  @Override
  public String chaveSegredo() {
    return chaveSegredo;
  }

  @Override
  public String descricao() {
    return "HMAC-SHA256 hex em " + cabecalho;
  }

  @Override
  public Resultado conferir(Configuracao config, EventoEntrada evento) {
    Segredo segredo = config.segredo(chaveSegredo);
    if (segredo.vazio()) {
      return Resultado.naoConfigurado(
          "sem segredo de webhook em '" + chaveSegredo + "': webhook nao pode ser conferido");
    }
    String recebida = evento.cabecalho(cabecalho);
    if (recebida == null || recebida.isBlank()) {
      return Resultado.falhou("assinatura.ausente", "cabecalho " + cabecalho + " nao veio");
    }
    String normalizada = recebida.trim().toLowerCase(Locale.ROOT);
    String prefixoEsperado = prefixo.toLowerCase(Locale.ROOT);
    if (!normalizada.startsWith(prefixoEsperado)) {
      return Resultado.falhou("assinatura.prefixo",
          "esperado prefixo '" + prefixo + "' em " + cabecalho);
    }
    String hexRecebido = normalizada.substring(prefixoEsperado.length());
    if (Bytes.deHex(hexRecebido) == null) {
      return Resultado.falhou("assinatura.malformada", "assinatura nao e' hexadecimal");
    }
    String calculada = Hmac.hex("HmacSHA256", segredo, evento.corpo());
    if (!Bytes.iguaisTempoConstante(calculada, hexRecebido)) {
      return Resultado.falhou("assinatura.invalida", "HMAC nao confere");
    }
    return Resultado.ok("assinatura conferida");
  }
}
