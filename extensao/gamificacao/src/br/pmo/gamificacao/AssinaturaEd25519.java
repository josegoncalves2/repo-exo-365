package br.pmo.gamificacao;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;

/**
 * Esquema Ed25519, que e' como o Discord assina as requisicoes que envia.
 *
 * <p>A diferenca em relacao ao HMAC nao e' de forca, e' de NATUREZA. HMAC usa um
 * segredo compartilhado: os dois lados conhecem a mesma chave, e portanto o
 * servidor que confere tambem teria como FORJAR uma requisicao. Ed25519 e'
 * assinatura assimetrica: aqui so' existe a chave PUBLICA do Discord. Ela
 * confere assinatura e nao produz nenhuma. Se o conteudo deste servidor vazar
 * inteiro, o atacante ainda nao consegue forjar um evento do Discord.
 *
 * <p>Por isso a chave publica NAO e' marcada como segredo na configuracao: ela e'
 * publica de verdade, o Discord a exibe no painel do desenvolvedor. Marca-la
 * como segredo daria uma falsa sensacao de que ha' algo a proteger ali e
 * confundiria quem opera.
 *
 * <p>A mensagem assinada e' {@code carimbo + corpo}, com o carimbo vindo em
 * {@code X-Signature-Timestamp}.
 *
 * <p>DETALHE DE IMPLEMENTACAO QUE NAO E' OBVIO: o JDK quer a chave publica como
 * {@link EdECPoint} -- coordenada Y como inteiro e um bit dizendo se X e' impar.
 * O Discord entrega os 32 bytes crus do formato RFC 8032, que sao Y em ordem
 * little-endian com o bit mais alto do ultimo byte carregando o sinal de X. A
 * conversao abaixo faz exatamente essa traducao; sem ela o JDK recusa a chave.
 */
public final class AssinaturaEd25519 implements Assinatura {

  private static final String CABECALHO_ASSINATURA = "X-Signature-Ed25519";

  private static final String CABECALHO_CARIMBO = "X-Signature-Timestamp";

  private static final int TAMANHO_CHAVE = 32;

  private final String chaveConfigPublica;

  /**
   * @param chaveConfigPublica chave de configuracao com a chave publica do
   *        provedor, em hexadecimal
   */
  public AssinaturaEd25519(String chaveConfigPublica) {
    this.chaveConfigPublica = chaveConfigPublica;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Aqui devolve a chave de configuracao da chave PUBLICA. Nao ha' segredo
   * neste esquema, e {@link Configuracao#segredo} nem aceitaria esta chave --
   * ela e' declarada como campo comum. Ver o javadoc da classe.
   */
  @Override
  public String chaveSegredo() {
    return chaveConfigPublica;
  }

  @Override
  public String descricao() {
    return "Ed25519 (assinatura assimetrica) em " + CABECALHO_ASSINATURA;
  }

  @Override
  public Resultado conferir(Configuracao config, EventoEntrada evento) {
    String hexChave = config.valor(chaveConfigPublica);
    if (hexChave == null || hexChave.isBlank()) {
      return Resultado.naoConfigurado("sem chave publica em '" + chaveConfigPublica
          + "': webhook nao pode ser conferido");
    }
    byte[] bytesChave = Bytes.deHex(hexChave.trim());
    if (bytesChave == null || bytesChave.length != TAMANHO_CHAVE) {
      return Resultado.falhou("chave.publica.invalida",
          "chave publica precisa ser " + TAMANHO_CHAVE + " bytes em hexadecimal");
    }
    String assinaturaHex = evento.cabecalho(CABECALHO_ASSINATURA);
    String carimbo = evento.cabecalho(CABECALHO_CARIMBO);
    if (assinaturaHex == null || assinaturaHex.isBlank()) {
      return Resultado.falhou("assinatura.ausente", CABECALHO_ASSINATURA + " nao veio");
    }
    if (carimbo == null || carimbo.isBlank()) {
      return Resultado.falhou("carimbo.ausente", CABECALHO_CARIMBO + " nao veio");
    }
    byte[] assinatura = Bytes.deHex(assinaturaHex.trim());
    if (assinatura == null) {
      return Resultado.falhou("assinatura.malformada", "assinatura nao e' hexadecimal");
    }
    byte[] carimboBytes = carimbo.getBytes(StandardCharsets.UTF_8);
    byte[] corpo = evento.corpo();
    byte[] mensagem = new byte[carimboBytes.length + corpo.length];
    System.arraycopy(carimboBytes, 0, mensagem, 0, carimboBytes.length);
    System.arraycopy(corpo, 0, mensagem, carimboBytes.length, corpo.length);
    try {
      PublicKey chave = chavePublica(bytesChave);
      Signature verificador = Signature.getInstance("Ed25519");
      verificador.initVerify(chave);
      verificador.update(mensagem);
      if (!verificador.verify(assinatura)) {
        return Resultado.falhou("assinatura.invalida", "Ed25519 nao confere");
      }
      return Resultado.ok("assinatura Ed25519 conferida");
    } catch (GeneralSecurityException e) {
      // Assinatura com tamanho errado ou ponto invalido cai aqui. E' entrada de
      // rede malformada, portanto FALHOU e nao excecao para cima.
      return Resultado.falhou("assinatura.invalida",
          "Ed25519 recusada: " + e.getClass().getSimpleName());
    }
  }

  /** Traduz os 32 bytes do RFC 8032 para o {@link EdECPublicKeySpec} do JDK. */
  private static PublicKey chavePublica(byte[] cruRfc8032) throws GeneralSecurityException {
    byte[] copia = cruRfc8032.clone();
    boolean xImpar = (copia[TAMANHO_CHAVE - 1] & 0x80) != 0;
    copia[TAMANHO_CHAVE - 1] &= (byte) 0x7F;
    // little-endian -> big-endian, que e' o que BigInteger entende.
    byte[] bigEndian = new byte[TAMANHO_CHAVE];
    for (int i = 0; i < TAMANHO_CHAVE; i++) {
      bigEndian[i] = copia[TAMANHO_CHAVE - 1 - i];
    }
    BigInteger y = new BigInteger(1, bigEndian);
    EdECPoint ponto = new EdECPoint(xImpar, y);
    EdECPublicKeySpec spec = new EdECPublicKeySpec(NamedParameterSpec.ED25519, ponto);
    return KeyFactory.getInstance("Ed25519").generatePublic(spec);
  }
}
