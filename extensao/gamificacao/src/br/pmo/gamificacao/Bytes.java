package br.pmo.gamificacao;

/**
 * Utilitarios de bytes usados na conferencia de assinatura de webhook.
 *
 * <p>POR QUE NAO USAR {@code Arrays.equals} PARA ASSINATURA. {@code Arrays.equals}
 * volta no primeiro byte diferente. Isso torna o tempo de resposta funcao do
 * tamanho do prefixo correto, e quem controla o cliente mede esse tempo: manda
 * milhares de requisicoes variando o primeiro byte, guarda o que demorou mais,
 * passa para o segundo, e reconstroi a assinatura byte a byte sem nunca ter
 * visto o segredo. E' o ataque de temporizacao classico, e e' pratico contra
 * servidor na mesma rede. As comparacoes daqui percorrem SEMPRE o vetor inteiro.
 */
public final class Bytes {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private Bytes() {
  }

  /**
   * Compara em tempo que nao depende de ONDE esta' a primeira diferenca.
   *
   * <p>O tamanho ainda influi -- vetores de tamanhos diferentes saem por um
   * caminho mais curto -- e isso e' aceitavel: o tamanho da assinatura e'
   * publico e fixo por algoritmo (32 bytes em SHA-256), entao nao ha' segredo a
   * proteger nele. O que nao pode vazar e' o CONTEUDO, e esse e' percorrido
   * inteiro sempre.
   *
   * <p>Nulo nunca e' igual a nada, nem a outro nulo: assinatura ausente tem de
   * ser recusa, jamais "os dois estao vazios, entao conferem".
   */
  public static boolean iguaisTempoConstante(byte[] a, byte[] b) {
    if (a == null || b == null) {
      return false;
    }
    int diferenca = a.length ^ b.length;
    int n = Math.min(a.length, b.length);
    for (int i = 0; i < n; i++) {
      diferenca |= a[i] ^ b[i];
    }
    return diferenca == 0;
  }

  /** Versao para texto hexadecimal, comparado sem diferenciar maiuscula. */
  public static boolean iguaisTempoConstante(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return iguaisTempoConstante(
        a.toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8),
        b.toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** Bytes para hexadecimal minusculo. */
  public static String paraHex(byte[] dados) {
    StringBuilder sb = new StringBuilder(dados.length * 2);
    for (byte b : dados) {
      sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
    }
    return sb.toString();
  }

  /**
   * Hexadecimal para bytes.
   *
   * @return {@code null} se o texto nao for hexadecimal valido. Devolver nulo em
   *         vez de levantar excecao e' deliberado: esta funcao le cabecalho
   *         vindo da rede, onde lixo e' entrada esperada e nao acidente de
   *         programacao. O chamador trata como assinatura invalida.
   */
  public static byte[] deHex(String hex) {
    if (hex == null || hex.length() % 2 != 0 || hex.isEmpty()) {
      return null;
    }
    byte[] saida = new byte[hex.length() / 2];
    for (int i = 0; i < saida.length; i++) {
      int alto = Character.digit(hex.charAt(i * 2), 16);
      int baixo = Character.digit(hex.charAt(i * 2 + 1), 16);
      if (alto < 0 || baixo < 0) {
        return null;
      }
      saida[i] = (byte) ((alto << 4) | baixo);
    }
    return saida;
  }
}
