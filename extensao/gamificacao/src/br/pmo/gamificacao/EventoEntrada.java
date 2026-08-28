package br.pmo.gamificacao;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Uma requisicao de webhook RECEBIDA de um provedor externo, ainda nao confiada.
 *
 * <p>POR QUE O CORPO E' GUARDADO COMO BYTES, e nao como String. A assinatura do
 * provedor cobre os BYTES exatos que ele enviou. Converter para String e depois
 * de volta para bytes nao e' operacao neutra: normalizacao de UTF-8, byte-order
 * mark e diferenca de fim de linha mudam um ou dois bytes, o HMAC recalculado da
 * diferente do enviado, e webhook legitimo passa a ser recusado de forma
 * intermitente e impossivel de depurar. Guardando os bytes crus, o HMAC e'
 * calculado sobre o mesmo material que o provedor assinou.
 *
 * <p>POR QUE OS CABECALHOS SAO INSENSIVEIS A MAIUSCULA: o RFC 7230 diz que nome
 * de cabecalho nao diferencia maiuscula, e na pratica os provedores variam
 * ({@code X-Hub-Signature-256} contra {@code x-hub-signature-256}) e proxies
 * reescrevem. Comparar com {@code equals} recusaria assinatura valida conforme o
 * proxy da frente.
 */
public final class EventoEntrada {

  private final Map<String, String> cabecalhos;

  private final byte[] corpo;

  public EventoEntrada(Map<String, String> cabecalhos, byte[] corpo) {
    Map<String, String> mapa = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (cabecalhos != null) {
      for (Map.Entry<String, String> e : cabecalhos.entrySet()) {
        if (e.getKey() != null && e.getValue() != null) {
          mapa.put(e.getKey(), e.getValue());
        }
      }
    }
    this.cabecalhos = Collections.unmodifiableMap(mapa);
    this.corpo = corpo == null ? new byte[0] : corpo.clone();
  }

  /** Atalho para montar evento com corpo em texto, usado nas provas. */
  public static EventoEntrada deTexto(Map<String, String> cabecalhos, String corpo) {
    return new EventoEntrada(cabecalhos,
        corpo == null ? new byte[0] : corpo.getBytes(StandardCharsets.UTF_8));
  }

  /** Cabecalho pelo nome, sem diferenciar maiuscula. {@code null} se ausente. */
  public String cabecalho(String nome) {
    return cabecalhos.get(Objects.requireNonNull(nome, "nome"));
  }

  /** Copia dos bytes crus. Copia para o chamador nao poder alterar o assinado. */
  public byte[] corpo() {
    return corpo.clone();
  }

  /** O corpo interpretado como UTF-8, para depois da assinatura conferida. */
  public String corpoTexto() {
    return new String(corpo, StandardCharsets.UTF_8);
  }

  public int tamanhoCorpo() {
    return corpo.length;
  }

  /**
   * Nao imprime o corpo nem os valores de cabecalho: cabecalho de webhook
   * carrega a propria assinatura e, em varios provedores, token de verificacao.
   * So' os NOMES dos cabecalhos saem, que e' o que ajuda a depurar.
   */
  @Override
  public String toString() {
    return "EventoEntrada[cabecalhos=" + cabecalhos.keySet()
        + ", corpo=" + corpo.length + " bytes]";
  }

  /** Nomes de cabecalho recebidos, em minusculo. */
  public java.util.Set<String> nomesCabecalho() {
    java.util.Set<String> nomes = new java.util.TreeSet<>();
    for (String n : cabecalhos.keySet()) {
      nomes.add(n.toLowerCase(Locale.ROOT));
    }
    return nomes;
  }
}
