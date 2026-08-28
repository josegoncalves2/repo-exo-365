package br.pmo.gamificacao;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Calculo de HMAC sobre bytes crus.
 *
 * <p>POR QUE UMA CLASSE SO' PARA ISSO: para que exista UM lugar onde o segredo
 * e' revelado e transformado em chave, e esse lugar seja curto o bastante para
 * ser lido inteiro. Espalhar {@code Mac.getInstance} pelos conectores
 * multiplicaria os pontos onde alguem pode, por descuido, registrar a chave em
 * log.
 */
public final class Hmac {

  private Hmac() {
  }

  /**
   * HMAC dos bytes, em hexadecimal minusculo.
   *
   * <p>{@code NoSuchAlgorithmException} e {@code InvalidKeyException} viram
   * {@link IllegalStateException} porque nenhuma das duas e' recuperavel em
   * tempo de execucao: HmacSHA256 e' exigido pela especificacao da plataforma
   * Java, e chave invalida aqui so' aconteceria com segredo vazio -- caso que os
   * chamadores ja' barram antes, conferindo {@link Segredo#presente()}.
   */
  public static String hex(String algoritmo, Segredo segredo, byte[] mensagem) {
    return Bytes.paraHex(bruto(algoritmo, segredo, mensagem));
  }

  /** Idem, devolvendo os bytes. */
  public static byte[] bruto(String algoritmo, Segredo segredo, byte[] mensagem) {
    try {
      Mac mac = Mac.getInstance(algoritmo);
      mac.init(new SecretKeySpec(segredo.revelar().getBytes(StandardCharsets.UTF_8), algoritmo));
      return mac.doFinal(mensagem);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM sem " + algoritmo, e);
    } catch (InvalidKeyException e) {
      throw new IllegalStateException("chave HMAC invalida", e);
    }
  }
}
