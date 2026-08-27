package br.pmo.nuvem;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

/**
 * Prova do cliente WebDAV contra um servidor HTTP LOCAL do JDK.
 *
 * <p><b>POR QUE UM SERVIDOR LOCAL.</b> Testar contra a internet e' teste que
 * quebra quando a rede muda. {@code com.sun.net.httpserver.HttpServer} vive no
 * JDK -- o servidor "hostil" que responde multistatus malformado, 401, 404 e
 * XML com DOCTYPE e' montado aqui, deterministico.
 */
final class ProvaWebDav {

  private static final String MULTISTATUS_OK =
      "<?xml version=\"1.0\"?>"
      + "<d:multistatus xmlns:d=\"DAV:\">"
      + "<d:response><d:href>/arquivo.txt</d:href>"
      + "<d:propstat><d:prop>"
      + "<d:displayname>arquivo.txt</d:displayname>"
      + "<d:getcontentlength>5</d:getcontentlength>"
      + "<d:getetag>\"etag-1\"</d:getetag>"
      + "<d:getcontenttype>text/plain</d:getcontenttype>"
      + "<d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>"
      + "</d:response>"
      + "<d:response><d:href>/pasta/</d:href>"
      + "<d:propstat><d:prop>"
      + "<d:displayname>pasta</d:displayname>"
      + "<d:resourcetype><d:collection/></d:resourcetype></d:prop>"
      + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat>"
      + "</d:response>"
      + "</d:multistatus>";

  private ProvaWebDav() {
  }

  static void rodar() throws Exception {
    listaEModela();
    recusa401();
    recusa404();
    recusaMultistatusMalformado();
    recusaXxe();
    putEGet();
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

  private static WebDavCliente cliente(String base) {
    return new WebDavCliente(base, () -> "tok-teste", 5_000L);
  }

  private static void listaEModela() throws Exception {
    Prova.secao("WebDAV — PROPFIND lista e modela arquivo e pasta");
    HttpServer s = subir(18081, MULTISTATUS_OK, 207);
    try {
      var itens = cliente("http://127.0.0.1:18081/dav").listar(CaminhoNuvem.raiz());
      Prova.igual("dois itens", 2, itens.size());
      boolean temArquivo = false;
      boolean temPasta = false;
      for (ArquivoNuvem a : itens) {
        if (a.getNome().equals("arquivo.txt") && !a.ehPasta()) {
          temArquivo = true;
          Prova.igual("tamanho do arquivo", 5L, a.getTamanho());
          Prova.igual("etag do arquivo", "\"etag-1\"", a.getEtag());
          Prova.igual("mime do arquivo", "text/plain", a.getMime());
        }
        if (a.getNome().equals("pasta") && a.ehPasta()) {
          temPasta = true;
        }
      }
      Prova.certo("arquivo modelado", temArquivo);
      Prova.certo("pasta modelada", temPasta);
    } finally {
      s.stop(0);
    }
  }

  private static void recusa401() throws Exception {
    Prova.secao("WebDAV — 401 vira SemPermissao, nunca 'lista vazia'");
    HttpServer s = subir(18082, "unauthorized", 401);
    try {
      boolean semPermissao = false;
      try {
        cliente("http://127.0.0.1:18082/dav").listar(CaminhoNuvem.raiz());
      } catch (WebDavCliente.SemPermissaoException e) {
        semPermissao = true;
      }
      Prova.certo("401 -> SemPermissaoException", semPermissao);
    } finally {
      s.stop(0);
    }
  }

  private static void recusa404() throws Exception {
    Prova.secao("WebDAV — 404 vira NaoEncontrado");
    HttpServer s = subir(18083, "not found", 404);
    try {
      boolean naoEncontrado = false;
      try {
        cliente("http://127.0.0.1:18083/dav").listar(CaminhoNuvem.de("/sumiu"));
      } catch (WebDavCliente.NaoEncontradoException e) {
        naoEncontrado = true;
      }
      Prova.certo("404 -> NaoEncontradoException", naoEncontrado);
    } finally {
      s.stop(0);
    }
  }

  private static void recusaMultistatusMalformado() throws Exception {
    Prova.secao("WebDAV — multistatus malformado vira WebDavException");
    HttpServer s = subir(18084, "isto nao e' xml", 207);
    try {
      boolean falhou = false;
      try {
        cliente("http://127.0.0.1:18084/dav").listar(CaminhoNuvem.raiz());
      } catch (WebDavCliente.WebDavException e) {
        falhou = true;
      }
      Prova.certo("malformado -> WebDavException", falhou);
    } finally {
      s.stop(0);
    }
  }

  /** XML com DOCTYPE/entidade externa tem de ser RECUSADO pelo parser. */
  private static void recusaXxe() throws Exception {
    Prova.secao("WebDAV — XXE desabilitado: DOCTYPE nao passa");
    String xxe = "<?xml version=\"1.0\"?>"
        + "<!DOCTYPE multistatus [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
        + "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>&xxe;</d:href>"
        + "<d:propstat><d:prop><d:displayname>x</d:displayname></d:prop>"
        + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
    HttpServer s = subir(18085, xxe, 207);
    try {
      boolean recusou = false;
      try {
        cliente("http://127.0.0.1:18085/dav").listar(CaminhoNuvem.raiz());
      } catch (WebDavCliente.WebDavException e) {
        recusou = true;
      }
      Prova.certo("DOCTYPE com entidade externa -> WebDavException", recusou);
    } finally {
      s.stop(0);
    }
  }

  private static void putEGet() throws Exception {
    Prova.secao("WebDAV — PUT e GET falam com o servidor local");
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 18086), 0);
    // PUT -> 201; GET -> corpo "olá!". Diferenciado pelo metodo.
    s.createContext("/", ex -> {
      if ("PUT".equals(ex.getRequestMethod())) {
        ex.sendResponseHeaders(201, -1);
        return;
      }
      byte[] corpo = "ol\u00e1!".getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, corpo.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(corpo);
      }
    });
    s.start();
    try {
      WebDavCliente c = cliente("http://127.0.0.1:18086/dav");
      c.enviar(CaminhoNuvem.de("/nota.txt"), "ol\u00e1!".getBytes(StandardCharsets.UTF_8),
               "text/plain; charset=utf-8");
      byte[] baixado = c.baixar(CaminhoNuvem.de("/nota.txt"));
      Prova.igual("GET devolve o que PUT enviou", "ol\u00e1!",
                  new String(baixado, StandardCharsets.UTF_8));
    } finally {
      s.stop(0);
    }
  }
}
