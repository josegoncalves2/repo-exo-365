package br.pmo.nuvem;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

/**
 * Prova do cliente OAuth2 contra um servidor LOCAL.
 */
final class ProvaOAuth2 {

  private ProvaOAuth2() {
  }

  static void rodar() throws Exception {
    urlDeAutorizacao();
    trocaDeCodigo();
    refreshERevogacao();
  }

  private static OAuth2Cliente cliente(String tokenUrl) {
    return new OAuth2Cliente("http://127.0.0.1:18091/authorize", tokenUrl,
                             "cliente-123", "segredo-abc", "http://portal/cb",
                             5_000L);
  }

  private static void urlDeAutorizacao() {
    Prova.secao("OAuth2 — URL de autorizacao");
    OAuth2Cliente c = cliente("http://x/token");
    String url = c.urlAutorizacao("estado-1");
    Prova.certo("traz response_type=code", url.contains("response_type=code"));
    Prova.certo("traz client_id", url.contains("client_id=cliente-123"));
    Prova.certo("traz redirect_uri", url.contains("redirect_uri="));
    Prova.certo("traz state", url.contains("state=estado-1"));
    Prova.certo("NAO traz segredo na URL (consulta)", !url.contains("segredo-abc"));
  }

  private static void trocaDeCodigo() throws Exception {
    Prova.secao("OAuth2 — troca de codigo por tokens");
    String json = "{\"access_token\":\"tok-acesso\",\"refresh_token\":\"tok-refresh\","
        + "\"expires_in\":3600}";
    HttpServer s = subir(18092, json, 200);
    try {
      OAuth2Cliente.Tokens t = cliente("http://127.0.0.1:18092/token").trocarCodigo("cod-1");
      Prova.igual("access_token", "tok-acesso", t.getAcesso());
      Prova.igual("refresh_token", "tok-refresh", t.getRefresh());
      Prova.certo("expiraEm no futuro", t.getExpiraEm() > System.currentTimeMillis());
      Prova.certo("nao expirado agora", !t.expirado(System.currentTimeMillis()));
    } finally {
      s.stop(0);
    }
  }

  private static void refreshERevogacao() throws Exception {
    Prova.secao("OAuth2 — refresh funciona; invalid_grant vira TokenExpirado");
    HttpServer s = subir(18093,
        "{\"access_token\":\"tok-novo\",\"refresh_token\":\"tok-novo-refresh\","
        + "\"expires_in\":3600}", 200);
    try {
      OAuth2Cliente.Tokens t = cliente("http://127.0.0.1:18093/token").renovar("tok-refresh");
      Prova.igual("renovou o acesso", "tok-novo", t.getAcesso());
    } finally {
      s.stop(0);
    }

    HttpServer s2 = subir(18094, "{\"error\":\"invalid_grant\"}", 400);
    try {
      boolean revogado = false;
      try {
        cliente("http://127.0.0.1:18094/token").renovar("tok-morto");
      } catch (OAuth2Cliente.TokenExpiradoException e) {
        revogado = true;
      }
      Prova.certo("invalid_grant -> TokenExpiradoException", revogado);
    } finally {
      s2.stop(0);
    }
  }

  private static HttpServer subir(int porta, String resposta, int status)
      throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", porta), 0);
    s.createContext("/", ex -> {
      byte[] corpo = resposta.getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(status, corpo.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(corpo);
      }
    });
    s.start();
    return s;
  }
}
