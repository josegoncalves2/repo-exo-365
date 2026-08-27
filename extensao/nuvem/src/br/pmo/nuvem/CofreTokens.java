package br.pmo.nuvem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cofre de credenciais em memoria: tokens de acesso, refresh e estado OAuth2.
 *
 * <p><b>POR QUE EM MEMORIA.</b> A plataforma guarda provedor e chave no banco
 * pelo proprio addon; este cofre e' a camada de runtime que mantem o token de
 * acesso VIVO entre chamadas sem tocar em disco. Token em disco e' token que
 * vaza em backup, em core dump e em imagem de container.
 *
 * <p><b>O QUE NUNCA ENTRA AQUI.</b> Nada que este cofre segura aparece em
 * {@code toString()} nem em log. O metodo {@link #digest(String)} existe para
 * quem precisa IDENTIFICAR um token em log (ex.: "token hash c3f9...") sem
 * revelar o token.
 *
 * <p><b>ESTADO OAuth2.</b> O valor de {@code state} anti-CSRF e' gerado aqui
 * com {@link SecureRandom} e guardado com o tempo de emissao: confere-se no
 * callback e rejeita-se state expirado, para o state roubado nao virar
 * autorizacao.
 */
public final class CofreTokens {

  /** Entrada de um state OAuth2 pendente. */
  public static final class EstadoOAuth2 {
    private final String estado;
    private final long emitidoEm;

    EstadoOAuth2(String estado, long emitidoEm) {
      this.estado = estado;
      this.emitidoEm = emitidoEm;
    }

    public String getEstado() {
      return estado;
    }

    public long getEmitidoEm() {
      return emitidoEm;
    }

    @Override
    public String toString() {
      return "EstadoOAuth2[emitidoEm=" + emitidoEm + "]";
    }
  }

  /** Validade padrao do state anti-CSRF: 10 minutos. */
  public static final long STATE_VALIDADE_MS = 600_000L;

  private final SecureRandom aleatorio = new SecureRandom();
  private final Map<String, String> tokens = new ConcurrentHashMap<>();
  private final Map<String, Long> expiraEm = new ConcurrentHashMap<>();
  private final Map<String, String> refresh = new ConcurrentHashMap<>();
  private final Map<String, EstadoOAuth2> estados = new ConcurrentHashMap<>();

  /** Guarda o token de acesso de um usuario/provedor. */
  public void guardar(String chave, OAuth2Cliente.Tokens token) {
    if (chave == null || chave.isEmpty()) {
      throw new IllegalArgumentException("chave vazia");
    }
    tokens.put(chave, token.getAcesso());
    expiraEm.put(chave, token.getExpiraEm());
    if (token.getRefresh() != null) {
      refresh.put(chave, token.getRefresh());
    }
  }

  /** Token de acesso vigente, ou null. Nunca valida por expiracao aqui. */
  public String acesso(String chave) {
    return tokens.get(chave);
  }

  /** Token de refresh, ou null. */
  public String refresh(String chave) {
    return refresh.get(chave);
  }

  /** Verdadeiro quando o token de acesso esta' expirado (ou ausente). */
  public boolean expirado(String chave, long agora) {
    Long exp = expiraEm.get(chave);
    return exp == null || agora >= exp;
  }

  /** Remove todas as credenciais de uma chave (logout/desconexao). */
  public void revogar(String chave) {
    tokens.remove(chave);
    expiraEm.remove(chave);
    refresh.remove(chave);
  }

  /** Hash SHA-256 do token, para log seguro ("hash c3f9..."). */
  public static String digest(String valor) {
    if (valor == null || valor.isEmpty()) {
      return "(vazio)";
    }
    try {
      byte[] h = MessageDigest.getInstance("SHA-256")
          .digest(valor.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(h).substring(0, 8);
    } catch (NoSuchAlgorithmException e) {
      return "(indisponivel)";
    }
  }

  /** Gera um state anti-CSRF novo e o guarda com a emissao. */
  public EstadoOAuth2 novoEstado() {
    byte[] b = new byte[24];
    aleatorio.nextBytes(b);
    String estado = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    EstadoOAuth2 e = new EstadoOAuth2(estado, System.currentTimeMillis());
    estados.put(estado, e);
    return e;
  }

  /**
   * Confere e consome um state no callback. {@code true} apenas quando o state
   * existe, foi emitido ha' menos de {@link #STATE_VALIDADE_MS} e NAO foi usado
   * antes (consumo = remocao).
   */
  public boolean conferirState(String estado, long agora) {
    if (estado == null) {
      return false;
    }
    EstadoOAuth2 e = estados.remove(estado);
    if (e == null) {
      return false;
    }
    return agora - e.getEmitidoEm() <= STATE_VALIDADE_MS;
  }

  /** Remove um state pendente (cancelamento da autorizacao). */
  public void descartarState(String estado) {
    estados.remove(estado);
  }
}
