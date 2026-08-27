package br.pmo.wopi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * O {@code access_token} do WOPI: quem pode mexer em qual arquivo, ate' quando.
 *
 * <h2>Por que este token existe e por que ele e' o ponto critico</h2>
 *
 * No protocolo WOPI quem busca o arquivo NAO e' o navegador do usuario: e' o
 * SERVIDOR do editor (Collabora, Office Online Server). Ele chega ao portal sem
 * cookie, sem sessao e sem qualquer vinculo com o login ; a UNICA coisa que ele
 * apresenta e' este token, recebido na URL de edicao.
 *
 * Ou seja: <b>este token e' a autenticacao inteira daquele acesso</b>. Um token
 * fraco aqui nao degrada uma funcionalidade; abre o acervo.
 *
 * <h2>As quatro amarras</h2>
 *
 * <ol>
 *   <li><b>Assinado</b> com HMAC-SHA256 sobre um segredo que nunca sai do
 *       servidor. Token opaco "aleatorio" guardado em tabela seria equivalente,
 *       mas exigiria estado compartilhado e limpeza; assinatura nao exige nem
 *       um nem outro.</li>
 *   <li><b>Amarrado ao ARQUIVO</b>. Sem isso, um token legitimo para um
 *       documento publico serviria para buscar qualquer outro ; e o editor
 *       pede o arquivo pelo id que estiver na URL.</li>
 *   <li><b>Amarrado ao USUARIO</b>, para a gravacao ser atribuida a quem
 *       realmente editou, e para revogar por pessoa.</li>
 *   <li><b>Com validade curta</b>. O token viaja na URL: entra em historico de
 *       navegador, em log de proxy e em cabecalho Referer. Um token sem prazo
 *       vazado nesses lugares e' acesso permanente.</li>
 * </ol>
 *
 * <h2>Comparacao em tempo constante</h2>
 *
 * A conferencia usa {@link MessageDigest#isEqual}, e nao {@code String.equals}.
 * Comparacao que sai no primeiro byte diferente vaza, pelo TEMPO de resposta,
 * quantos bytes do prefixo estavam certos ; e com isso se descobre uma
 * assinatura valida byte a byte, sem nunca ter tido o segredo. E' ataque
 * praticavel contra servico em rede local, que e' exatamente o caso aqui.
 *
 * <p>Sem estado e sem I/O: da' para provar cada propriedade isoladamente.
 */
public final class TokenWopi {

  /** Separador dos campos. Nao pode ocorrer nos campos ; ver a validacao. */
  private static final char SEPARADOR = '|';

  private static final String ALGORITMO = "HmacSHA256";

  private final byte[] segredo;

  /**
   * @param segredo material de chave; exigido com pelo menos 32 bytes porque
   *        HMAC-SHA256 com chave curta reduz o custo de forca bruta ao da
   *        chave, e nao ao do algoritmo
   */
  public TokenWopi(byte[] segredo) {
    if (segredo == null || segredo.length < 32) {
      throw new IllegalArgumentException(
          "segredo do token WOPI precisa de pelo menos 32 bytes; "
          + "chave curta e' o elo fraco do HMAC");
    }
    this.segredo = segredo.clone();
  }

  public static TokenWopi comSegredoTextual(String segredo) {
    if (segredo == null) {
      throw new IllegalArgumentException("segredo nulo");
    }
    return new TokenWopi(segredo.getBytes(StandardCharsets.UTF_8));
  }

  /** O que um token afirma. */
  public static final class Reivindicacao {
    private final String idArquivo;
    private final String usuario;
    private final long expiraEm;
    private final boolean podeGravar;

    public Reivindicacao(String idArquivo, String usuario, long expiraEm, boolean podeGravar) {
      if (idArquivo == null || idArquivo.isEmpty()) {
        throw new IllegalArgumentException("token sem arquivo nao amarra nada");
      }
      if (usuario == null || usuario.isEmpty()) {
        throw new IllegalArgumentException("token sem usuario nao pode ser atribuido");
      }
      if (idArquivo.indexOf(SEPARADOR) >= 0 || usuario.indexOf(SEPARADOR) >= 0) {
        // Campo contendo o separador permitiria mover a fronteira entre campos
        // mantendo o texto assinado identico ; um id "a|joao" e um usuario
        // "joao" produziriam a mesma carga que id "a" e usuario "joao".
        // E' confusao de delimitador, e da' troca de identidade.
        throw new IllegalArgumentException(
            "arquivo e usuario nao podem conter '" + SEPARADOR + "'");
      }
      this.idArquivo = idArquivo;
      this.usuario = usuario;
      this.expiraEm = expiraEm;
      this.podeGravar = podeGravar;
    }

    public String getIdArquivo() {
      return idArquivo;
    }

    public String getUsuario() {
      return usuario;
    }

    public long getExpiraEm() {
      return expiraEm;
    }

    public boolean podeGravar() {
      return podeGravar;
    }

    String carga() {
      return idArquivo + SEPARADOR + usuario + SEPARADOR + expiraEm
             + SEPARADOR + (podeGravar ? "w" : "r");
    }
  }

  /** Emite o token. */
  public String emitir(Reivindicacao reivindicacao) {
    if (reivindicacao == null) {
      throw new IllegalArgumentException("reivindicacao nula");
    }
    String carga = reivindicacao.carga();
    String assinatura = base64(assinar(carga));
    return base64(carga.getBytes(StandardCharsets.UTF_8)) + "." + assinatura;
  }

  /**
   * Confere e devolve o que o token afirma.
   *
   * @param agoraMillis instante de referencia, injetado para a validade poder
   *        ser provada sem esperar o relogio andar
   * @return a reivindicacao, ou {@code null} quando o token e' invalido,
   *         adulterado ou vencido. Nao ha' excecao distinguindo os casos, e e'
   *         de proposito: dizer ao chamador "a assinatura estava certa mas
   *         venceu" e' informacao gratuita para quem esta' sondando.
   */
  public Reivindicacao conferir(String token, long agoraMillis) {
    if (token == null || token.isEmpty()) {
      return null;
    }
    int ponto = token.indexOf('.');
    if (ponto <= 0 || ponto == token.length() - 1) {
      return null;
    }
    // Mais de um ponto e' token malformado, nao um campo com ponto dentro.
    if (token.indexOf('.', ponto + 1) >= 0) {
      return null;
    }

    byte[] cargaBytes;
    byte[] assinaturaRecebida;
    try {
      cargaBytes = Base64.getUrlDecoder().decode(token.substring(0, ponto));
      assinaturaRecebida = Base64.getUrlDecoder().decode(token.substring(ponto + 1));
    } catch (IllegalArgumentException e) {
      return null;
    }

    String carga = new String(cargaBytes, StandardCharsets.UTF_8);
    byte[] esperada = assinar(carga);

    // TEMPO CONSTANTE. Ver o javadoc da classe.
    if (!MessageDigest.isEqual(esperada, assinaturaRecebida)) {
      return null;
    }

    String[] campos = carga.split("\\" + SEPARADOR);
    if (campos.length != 4) {
      return null;
    }
    long expiraEm;
    try {
      expiraEm = Long.parseLong(campos[2]);
    } catch (NumberFormatException e) {
      return null;
    }
    if (agoraMillis >= expiraEm) {
      return null;
    }
    boolean podeGravar = "w".equals(campos[3]);

    try {
      return new Reivindicacao(campos[0], campos[1], expiraEm, podeGravar);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * O token confere E vale para ESTE arquivo?
   *
   * <p>Existe como metodo proprio porque conferir a assinatura e esquecer de
   * comparar o arquivo e' o erro classico de implementacao de WOPI: o token
   * fica valido, e serve para buscar qualquer documento cujo id o cliente
   * escrever na URL.
   */
  public Reivindicacao conferirPara(String token, String idArquivo, long agoraMillis) {
    Reivindicacao r = conferir(token, agoraMillis);
    if (r == null || idArquivo == null || !r.getIdArquivo().equals(idArquivo)) {
      return null;
    }
    return r;
  }

  private byte[] assinar(String carga) {
    try {
      Mac mac = Mac.getInstance(ALGORITMO);
      mac.init(new SecretKeySpec(segredo, ALGORITMO));
      return mac.doFinal(carga.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException e) {
      // Algoritmo ausente ou chave recusada e' defeito de ambiente, nao
      // condicao de execucao. Devolver "assinatura vazia" faria todo token
      // conferir contra todo token.
      throw new IllegalStateException("HMAC indisponivel: " + e.getMessage(), e);
    }
  }

  private static String base64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
