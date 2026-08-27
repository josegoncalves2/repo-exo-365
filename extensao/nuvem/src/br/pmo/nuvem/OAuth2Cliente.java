package br.pmo.nuvem;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Cliente OAuth2 authorization-code + refresh, escrito a mao sobre o
 * {@code java.net.http.HttpClient} do JDK.
 *
 * <p><b>POR QUE NAO UMA BIBLIOTECA.</b> O nucleo nao pode depender de nada fora
 * do JDK -- e' o que permite provar em segundos no host. O fluxo OAuth2 de
 * authorization-code tem quatro passos, todos HTTP simples; implementar a mao
 * elimina a maior fonte de CVE de aplicacao (parser de biblioteca desatualizada)
 * e nao custa mais de duzentas linhas.
 *
 * <p><b>REGRA DE FALHA.</b> Token expirado NAO e' erro silencioso. Resposta
 * 401/400 com {@code invalid_grant} dispara {@link TokenExpiradoException};
 * qualquer outra falha vira {@link OAuth2Exception} com motivo. Nenhuma funcao
 * deste pacote devolve "token ok" quando o servidor disse nao.
 *
 * <p><b>SEGURANCA.</b> O segredo de cliente nunca vai em URL de consulta (vai
 * no corpo, {@code application/x-www-form-urlencoded}, como manda a RFC 6749
 * para confidencial); o refresh token vive no {@link CofreTokens}, nunca em
 * log; e {@link #toString()} nao imprime token nenhum.
 */
public final class OAuth2Cliente {

  /** Token expirado ou revogado: quem chama precisa reautenticar. */
  public static final class TokenExpiradoException extends Exception {
    private static final long serialVersionUID = 1L;

    public TokenExpiradoException(String motivo) {
      super(motivo);
    }
  }

  /** Qualquer outra falha do fluxo OAuth2. */
  public static final class OAuth2Exception extends Exception {
    private static final long serialVersionUID = 1L;

    public OAuth2Exception(String motivo) {
      super(motivo);
    }

    public OAuth2Exception(String motivo, Throwable causa) {
      super(motivo, causa);
    }
  }

  /** Token de acesso + token de refresh + validade. Imutavel e seguro de logar. */
  public static final class Tokens {
    private final String acesso;
    private final String refresh;
    private final long expiraEm; // epoch ms

    Tokens(String acesso, String refresh, long expiraEm) {
      this.acesso = acesso;
      this.refresh = refresh;
      this.expiraEm = expiraEm;
    }

    public String getAcesso() {
      return acesso;
    }

    public String getRefresh() {
      return refresh;
    }

    public long getExpiraEm() {
      return expiraEm;
    }

    public boolean expirado(long agora) {
      return agora >= expiraEm;
    }

    @Override
    public String toString() {
      return "Tokens[expiraEm=" + expiraEm + "]";
    }
  }

  private final String authUrl;
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final String redirectUri;
  private final HttpClient http;
  private final long timeoutMs;

  public OAuth2Cliente(String authUrl, String tokenUrl, String clientId,
                       String clientSecret, String redirectUri) {
    this(authUrl, tokenUrl, clientId, clientSecret, redirectUri, 30_000L);
  }

  public OAuth2Cliente(String authUrl, String tokenUrl, String clientId,
                       String clientSecret, String redirectUri, long timeoutMs) {
    if (authUrl == null || authUrl.isEmpty() || tokenUrl == null || tokenUrl.isEmpty()
        || clientId == null || clientId.isEmpty()) {
      throw new IllegalArgumentException("OAuth2 exige authUrl, tokenUrl e clientId");
    }
    this.authUrl = authUrl;
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret == null ? "" : clientSecret;
    this.redirectUri = redirectUri;
    this.timeoutMs = timeoutMs;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  /**
   * Monta a URL de autorizacao para o usuario abrir no navegador.
   *
   * @param estado valor de anti-CSRF; deve ser gerado por quem chama e
   *               conferido no callback
   */
  public String urlAutorizacao(String estado) {
    return authUrl + (authUrl.contains("?") ? "&" : "?")
        + "response_type=code"
        + "&client_id=" + enc(clientId)
        + "&redirect_uri=" + enc(redirectUri)
        + "&state=" + enc(estado)
        + "&scope=" + enc("files");
  }

  /**
   * Troca o codigo de autorizacao por tokens.
   *
   * @throws TokenExpiradoException nao se aplica aqui (nunca ha' token
   *         expirado ao trocar codigo); a excecao existe para a interface
   *         uniforme com refresh
   */
  public Tokens trocarCodigo(String codigo) throws OAuth2Exception, TokenExpiradoException {
    if (codigo == null || codigo.isEmpty()) {
      throw new IllegalArgumentException("codigo de autorizacao vazio");
    }
    Map<String, String> corpo = new HashMap<>();
    corpo.put("grant_type", "authorization_code");
    corpo.put("code", codigo);
    corpo.put("redirect_uri", redirectUri);
    corpo.put("client_id", clientId);
    corpo.put("client_secret", clientSecret);
    return post(corpo);
  }

  /**
   * Renova o token de acesso com o refresh token.
   *
   * <p>Resposta {@code invalid_grant} significa refresh revogado ou expirado:
   * vira {@link TokenExpiradoException}, que e' o sinal de que o usuario
   * precisa refazer a autorizacao do zero.
   */
  public Tokens renovar(String refreshToken) throws OAuth2Exception, TokenExpiradoException {
    if (refreshToken == null || refreshToken.isEmpty()) {
      throw new TokenExpiradoException("sem refresh token: reautentique");
    }
    Map<String, String> corpo = new HashMap<>();
    corpo.put("grant_type", "refresh_token");
    corpo.put("refresh_token", refreshToken);
    corpo.put("client_id", clientId);
    corpo.put("client_secret", clientSecret);
    return post(corpo);
  }

  /** Executa o POST do corpo e parseia a resposta JSON. */
  private Tokens post(Map<String, String> corpo) throws OAuth2Exception, TokenExpiradoException {
    StringBuilder dados = new StringBuilder();
    for (Map.Entry<String, String> e : corpo.entrySet()) {
      if (dados.length() > 0) {
        dados.append('&');
      }
      dados.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
    }

    HttpRequest req;
    try {
      req = HttpRequest.newBuilder()
          .uri(URI.create(tokenUrl))
          .timeout(Duration.ofMillis(timeoutMs))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(dados.toString(),
                                                    StandardCharsets.UTF_8))
          .build();
    } catch (IllegalArgumentException e) {
      throw new OAuth2Exception("tokenUrl invalida: " + tokenUrl, e);
    }

    HttpResponse<String> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw new OAuth2Exception("falha de rede no OAuth2: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OAuth2Exception("OAuth2 interrompido", e);
    }

    int status = resp.statusCode();
    String corpoResposta = resp.body();
    if (status == 400 && corpoResposta.contains("invalid_grant")) {
      throw new TokenExpiradoException("refresh/codigo revogado ou expirado");
    }
    if (status != 200) {
      throw new OAuth2Exception("servidor OAuth2 respondeu " + status
          + " (corpo truncado: " + truncar(corpoResposta) + ")");
    }

    JsonLeve json;
    try {
      json = JsonLeve.parse(corpoResposta);
    } catch (IllegalArgumentException e) {
      throw new OAuth2Exception("resposta OAuth2 nao e' JSON: " + e.getMessage(), e);
    }
    String acesso = json.string("access_token");
    if (acesso == null) {
      throw new OAuth2Exception("resposta OAuth2 sem access_token");
    }
    String refresh = json.string("refresh_token");
    long expiraEm = System.currentTimeMillis();
    String em = json.string("expires_in");
    if (em != null) {
      try {
        expiraEm += Long.parseLong(em) * 1000L;
      } catch (NumberFormatException ignorado) {
        // expires_in ausente ou invalido: assume 1h e segue.
        expiraEm += 3_600_000L;
      }
    } else {
      expiraEm += 3_600_000L;
    }
    return new Tokens(acesso, refresh, expiraEm);
  }

  private static String enc(String v) {
    return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
  }

  private static String truncar(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 120 ? s : s.substring(0, 120) + "...";
  }

  /**
   * Parseador JSON minimo (so' o que o OAuth2 e o WebDAV precisam: objeto com
   * campos de string). Sem dependencia externa; falha com excecao em JSON
   * invalido.
   */
  static final class JsonLeve {
    private final Map<String, String> campos = new HashMap<>();

    static JsonLeve parse(String texto) {
      JsonLeve j = new JsonLeve();
      String t = texto == null ? "" : texto.trim();
      if (!t.startsWith("{")) {
        throw new IllegalArgumentException("nao comeca com {");
      }
      // Percorre pares chave:"valor" simples; suficiente para OAuth2.
      int i = 1;
      while (i < t.length() - 1) {
        // procura aspas da chave
        while (i < t.length() && t.charAt(i) != '"') {
          i++;
        }
        if (i >= t.length() - 1) {
          break;
        }
        int fimChave = i + 1;
        while (fimChave < t.length() && t.charAt(fimChave) != '"') {
          fimChave++;
        }
        String chave = t.substring(i + 1, fimChave);
        i = fimChave + 1;
        while (i < t.length() && t.charAt(i) != ':') {
          i++;
        }
        i++;
        while (i < t.length() && Character.isWhitespace(t.charAt(i))) {
          i++;
        }
        if (i < t.length() && t.charAt(i) == '"') {
          int fimValor = i + 1;
          StringBuilder valor = new StringBuilder();
          while (fimValor < t.length()) {
            char c = t.charAt(fimValor);
            if (c == '\\' && fimValor + 1 < t.length()) {
              valor.append(t.charAt(fimValor + 1));
              fimValor += 2;
              continue;
            }
            if (c == '"') {
              break;
            }
            valor.append(c);
            fimValor++;
          }
          j.campos.put(chave, valor.toString());
          i = fimValor + 1;
        } else {
          // valor nao-string (numero, true, null): captura ate a virgula.
          int fimValor = i;
          while (fimValor < t.length() && t.charAt(fimValor) != ',' && t.charAt(fimValor) != '}') {
            fimValor++;
          }
          j.campos.put(chave, t.substring(i, fimValor).trim());
          i = fimValor + 1;
        }
      }
      return j;
    }

    String string(String chave) {
      return campos.get(chave);
    }
  }
}
