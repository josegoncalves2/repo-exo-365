package br.pmo.nuvem;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Cliente WebDAV para Nextcloud, escrito a mao sobre o JDK.
 *
 * <p><b>O QUE ELE FAZ.</b> PROPFIND (listar), GET (baixar), PUT (enviar),
 * MKCOL (criar pasta), MOVE (renomear/mover) e DELETE. Parse do XML multistatus
 * com o parser do JDK, XXE desabilitado: um servidor Nextcloud comprometido
 * responde XML, e XML com entidade externa e' o caminho classico de leitura de
 * arquivos locais e DoS.
 *
 * <p><b>REGRA DE FALHA.</b> Toda operacao distingue sucesso de falha:
 * <ul>
 *   <li>{@code 401} ou {@code 403} -> {@link SemPermissaoException};</li>
 *   <li>{@code 404} -> {@link NaoEncontradoException};</li>
 *   <li>{@code 409} (MKCOL em caminho com pai inexistente) -> falha normal;</li>
 *   <li>qualquer outro status fora do esperado -> {@link WebDavException} com o
 *       status e o corpo truncado. Nunca "sucesso" por engano.</li>
 * </ul>
 *
 * <p><b>TOKEN NA URL? NAO.</b> O token de acesso vai no cabeçalho
 * {@code Authorization: Bearer} ou {@code Basic}, nunca em query string: URL
 * com token aparece em log de proxy, em historico e em referer.
 */
public final class WebDavCliente {

  /** Falta de permissao (401/403): reautenticar ou permissao insuficiente. */
  public static final class SemPermissaoException extends Exception {
    private static final long serialVersionUID = 1L;

    public SemPermissaoException(String motivo) {
      super(motivo);
    }
  }

  /** Recurso nao existe (404). */
  public static final class NaoEncontradoException extends Exception {
    private static final long serialVersionUID = 1L;

    public NaoEncontradoException(String motivo) {
      super(motivo);
    }
  }

  /** Qualquer outra falha WebDAV, com status e corpo truncado. */
  public static final class WebDavException extends Exception {
    private static final long serialVersionUID = 1L;

    public WebDavException(String motivo) {
      super(motivo);
    }
  }

  private static final DateTimeFormatter DATA_NEXTCLOUD =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  private final String baseUrl;    // ex.: https://nuvem.pmo.gov.br/remote.php/dav/files/<usuario>
  private final HttpClient http;
  private final long timeoutMs;
  private final TokenSource tokens;

  /** Fonte do token de acesso; separada para o adaptador injetar o refresh. */
  public interface TokenSource {
    /** @throws Exception qualquer falha; quem chama traduz. */
    String acessar() throws Exception;
  }

  public WebDavCliente(String baseUrl, TokenSource tokens) {
    this(baseUrl, tokens, 60_000L);
  }

  public WebDavCliente(String baseUrl, TokenSource tokens, long timeoutMs) {
    if (baseUrl == null || baseUrl.isEmpty()) {
      throw new IllegalArgumentException("baseUrl vazia");
    }
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.tokens = tokens;
    this.timeoutMs = timeoutMs;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  /** Lista o conteudo de um diretorio remoto. */
  public List<ArquivoNuvem> listar(CaminhoNuvem pasta)
      throws SemPermissaoException, NaoEncontradoException, WebDavException {
    String corpo = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
        + "<d:propfind xmlns:d=\"DAV:\">"
        + "<d:prop><d:displayname/><d:getcontentlength/><d:getlastmodified/>"
        + "<d:getetag/><d:getcontenttype/><d:resourcetype/></d:prop></d:propfind>";
    HttpRequest req = base("PROPFIND", url(pasta))
        .header("Depth", "1")
        .header("Content-Type", "application/xml")
        .method("PROPFIND", HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8))
        .build();
    HttpResponse<String> resp = enviar(req);
    int status = resp.statusCode();
    if (status == 401 || status == 403) {
      throw new SemPermissaoException("PROPFIND " + pasta + " -> " + status);
    }
    if (status == 404) {
      throw new NaoEncontradoException("PROPFIND " + pasta + " -> 404");
    }
    if (status != 207) {
      throw new WebDavException("PROPFIND " + pasta + " -> " + status
          + " (esperado 207 multistatus): " + truncar(resp.body()));
    }
    return parseMultistatus(resp.body(), pasta);
  }

  /** Baixa o conteudo de um arquivo e devolve como array de bytes. */
  public byte[] baixar(CaminhoNuvem arquivo)
      throws SemPermissaoException, NaoEncontradoException, WebDavException {
    HttpRequest req = base("GET", url(arquivo)).build();
    HttpResponse<byte[]> resp = enviarBytes(req);
    int status = resp.statusCode();
    if (status == 401 || status == 403) {
      throw new SemPermissaoException("GET " + arquivo + " -> " + status);
    }
    if (status == 404) {
      throw new NaoEncontradoException("GET " + arquivo + " -> 404");
    }
    if (status != 200) {
      throw new WebDavException("GET " + arquivo + " -> " + status + " (esperado 200)");
    }
    return resp.body();
  }

  /** Envia o conteudo de um arquivo (PUT). */
  public void enviar(CaminhoNuvem arquivo, byte[] conteudo, String mime)
      throws SemPermissaoException, NaoEncontradoException, WebDavException {
    HttpRequest req = base("PUT", url(arquivo))
        .header("Content-Type", mime == null || mime.isEmpty() ? "application/octet-stream" : mime)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(conteudo))
        .build();
    HttpResponse<String> resp = enviar(req);
    int status = resp.statusCode();
    if (status == 401 || status == 403) {
      throw new SemPermissaoException("PUT " + arquivo + " -> " + status);
    }
    if (status == 404) {
      throw new NaoEncontradoException("PUT " + arquivo + " -> 404 (pai nao existe?)");
    }
    if (status != 201 && status != 204) {
      throw new WebDavException("PUT " + arquivo + " -> " + status + " (esperado 201/204)");
    }
  }

  /** Cria uma pasta (MKCOL). 405 = ja' existe; 409 = pai nao existe. */
  public void criarPasta(CaminhoNuvem pasta)
      throws SemPermissaoException, NaoEncontradoException, WebDavException {
    HttpRequest req = base("MKCOL", url(pasta)).build();
    HttpResponse<String> resp = enviar(req);
    int status = resp.statusCode();
    if (status == 401 || status == 403) {
      throw new SemPermissaoException("MKCOL " + pasta + " -> " + status);
    }
    if (status == 404) {
      throw new NaoEncontradoException("MKCOL " + pasta + " -> 404 (pai nao existe)");
    }
    if (status == 405) {
      // Ja' existe: para MKCOL isso nao e' erro, e' estado desejado.
      return;
    }
    if (status != 201) {
      throw new WebDavException("MKCOL " + pasta + " -> " + status + " (esperado 201 ou 405)");
    }
  }

  /** Move um arquivo ou pasta (MOVE com Destination). */
  public void mover(CaminhoNuvem origem, CaminhoNuvem destino)
      throws SemPermissaoException, NaoEncontradoException, WebDavException {
    HttpRequest req = base("MOVE", url(origem))
        .header("Destination", url(destino))
        .build();
    HttpResponse<String> resp = enviar(req);
    int status = resp.statusCode();
    if (status == 401 || status == 403) {
      throw new SemPermissaoException("MOVE " + origem + " -> " + status);
    }
    if (status == 404) {
      throw new NaoEncontradoException("MOVE " + origem + " -> 404");
    }
    if (status != 201 && status != 204) {
      throw new WebDavException("MOVE " + origem + " -> " + status + " (esperado 201/204)");
    }
  }

  /** Apaga um arquivo ou pasta (DELETE). 404 = ja' apagado, nao e' erro. */
  public void apagar(CaminhoNuvem alvo)
      throws SemPermissaoException, NaoEncontradoException, WebDavException {
    HttpRequest req = base("DELETE", url(alvo)).build();
    HttpResponse<String> resp = enviar(req);
    int status = resp.statusCode();
    if (status == 401 || status == 403) {
      throw new SemPermissaoException("DELETE " + alvo + " -> " + status);
    }
    if (status == 404) {
      return;
    }
    if (status != 204 && status != 200) {
      throw new WebDavException("DELETE " + alvo + " -> " + status + " (esperado 204)");
    }
  }

  // ===========================================================================

  private String url(CaminhoNuvem caminho) {
    String p = caminho.caminho();
    if ("/".equals(p)) {
      return baseUrl + "/";
    }
    return baseUrl + p;
  }

  private HttpRequest.Builder base(String metodo, String url) {
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofMillis(timeoutMs));
    String token;
    try {
      token = tokens.acessar();
    } catch (Exception e) {
      throw new IllegalStateException("nao foi possivel obter token de acesso: "
          + e.getMessage(), e);
    }
    if (token == null || token.isEmpty()) {
      throw new IllegalStateException("token de acesso vazio");
    }
    b.header("Authorization", "Bearer " + token);
    b.header("User-Agent", "PMO-Nuvem/1.0");
    return b;
  }

  private HttpResponse<String> enviar(HttpRequest req) throws WebDavException {
    try {
      return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new WebDavException("falha de rede: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WebDavException("operacao WebDAV interrompida");
    }
  }

  private HttpResponse<byte[]> enviarBytes(HttpRequest req) throws WebDavException {
    try {
      return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new WebDavException("falha de rede: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WebDavException("operacao WebDAV interrompida");
    }
  }

  /**
   * Parse do XML multistatus com XXE desabilitado. O servidor e' possivelmente
   * hostil: DOCTYPE, entidades externas e general entities sao proibidas.
   */
  private List<ArquivoNuvem> parseMultistatus(String xml, CaminhoNuvem pasta)
      throws WebDavException {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    try {
      f.setNamespaceAware(true); // multistatus vive em DAV:; sem isto, 0 itens
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
    } catch (Exception e) {
      throw new WebDavException("parser sem XXE indisponivel: " + e.getMessage());
    }
    Document doc;
    try {
      DocumentBuilder db = f.newDocumentBuilder();
      doc = db.parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new WebDavException("multistatus malformado: " + e.getMessage());
    }

    List<ArquivoNuvem> itens = new ArrayList<>();
    NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");
    for (int i = 0; i < responses.getLength(); i++) {
      Element resp = (Element) responses.item(i);
      String href = texto(resp, "DAV:", "href");
      if (href == null) {
        continue;
      }
      // href vem com URL absoluta ou caminho; extrai so' o caminho.
      String caminho = caminhoDeHref(href);
      if (caminho == null || "/".equals(caminho)) {
        continue; // a propria pasta na resposta do PROPFIND Depth=1
      }
      boolean ehPasta = temResourcetype(resp);
      long tamanho = 0;
      String etag = null;
      String mime = null;
      long modificado = 0;
      String s = texto(resp, "DAV:", "getcontentlength");
      if (s != null) {
        try {
          tamanho = Long.parseLong(s.trim());
        } catch (NumberFormatException ignorado) {
          // tamanho malformado de servidor hostil nao derruba o lote
        }
      }
      etag = texto(resp, "DAV:", "getetag");
      mime = texto(resp, "DAV:", "getcontenttype");
      String mod = texto(resp, "DAV:", "getlastmodified");
      if (mod != null) {
        try {
          modificado = Instant.from(DATA_NEXTCLOUD.parse(mod.trim())).toEpochMilli();
        } catch (Exception ignorado) {
          try {
            modificado = Instant.parse(mod.trim()).toEpochMilli();
          } catch (Exception ignorado2) {
            // data ilegivel: fica 0 (desconhecida)
          }
        }
      }
      try {
        CaminhoNuvem cn = CaminhoNuvem.de(caminho);
        itens.add(new ArquivoNuvem(caminho, cn.nome(), cn, tamanho, modificado,
                                   etag, mime, ehPasta));
      } catch (IllegalArgumentException e) {
        // caminho invalido vindo do servidor e' ignorado com registro: um
        // servidor hostil nao pode injetar no' no JCR, mas tambem nao pode
        // travar a listagem inteira.
        System.err.println("nuvem: caminho invalido ignorado do servidor: "
            + e.getMessage());
      }
    }
    return itens;
  }

  private String caminhoDeHref(String href) {
    if (href == null || href.isEmpty()) {
      return null;
    }
    // Tira o host: "https://nuvem/remote.php/dav/files/u/pasta" -> "/remote.php/dav/files/u/pasta"
    int esquema = href.indexOf("://");
    if (esquema >= 0) {
      int barra = href.indexOf('/', esquema + 3);
      href = barra >= 0 ? href.substring(barra) : "/";
    }
    return href;
  }

  private static String texto(Element pai, String ns, String nome) {
    NodeList lista = pai.getElementsByTagNameNS(ns, nome);
    if (lista.getLength() == 0) {
      return null;
    }
    Node n = lista.item(0);
    return n.getTextContent() == null ? null : n.getTextContent().trim();
  }

  private static boolean temResourcetype(Element resp) {
    NodeList rt = resp.getElementsByTagNameNS("DAV:", "resourcetype");
    for (int i = 0; i < rt.getLength(); i++) {
      Node n = rt.item(i);
      NodeList filhos = n.getChildNodes();
      for (int j = 0; j < filhos.getLength(); j++) {
        Node filho = filhos.item(j);
        if (filho.getNodeType() == Node.ELEMENT_NODE
            && "DAV:".equals(filho.getNamespaceURI())
            && "collection".equals(filho.getLocalName())) {
          return true;
        }
      }
    }
    return false;
  }

  private static String truncar(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 200 ? s : s.substring(0, 200) + "...";
  }
}
