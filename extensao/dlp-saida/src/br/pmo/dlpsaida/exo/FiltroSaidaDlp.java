package br.pmo.dlpsaida.exo;

import br.pmo.dlpsaida.ClienteDlp;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Filtro de SAIDA. Inspeciona o conteudo que esta' indo para o usuario e, se a
 * politica mandar, nao entrega.
 *
 * <p><b>A DIRECAO IMPORTA.</b> DLP nao existe para impedir o servidor publico de
 * SUBIR documento para o portal -- o portal e' o lugar autorizado do dado.
 * Existe para que o dado nao SAIA. Por isso este filtro olha a RESPOSTA, e nao
 * o corpo da requisicao.
 *
 * <p><b>POR QUE PRECISA BUFERIZAR.</b> Para saber o que esta' saindo e' preciso
 * ler o que esta' saindo. A resposta e' acumulada ate' um teto
 * ({@code exo.dlp.tetoBytes}, padrao 32 MiB) e so' entao entregue. Acima do
 * teto o conteudo passa em fluxo e um incidente e' registrado dizendo que NAO
 * foi inspecionado -- que e' honesto, e diferente de dizer que estava limpo.
 *
 * <p><b>O que NAO e' inspecionado:</b> recurso estatico (js, css, fonte,
 * imagem de tema) e resposta que nao seja de um dos caminhos de saida
 * declarados. Inspecionar tudo custaria memoria e nao acrescentaria protecao:
 * arquivo de tema nao carrega dado de cidadao.
 */
public class FiltroSaidaDlp implements org.exoplatform.web.filter.Filter {

  // IMPLEMENTA org.exoplatform.web.filter.Filter, e NAO jakarta.servlet.Filter.
  // Sao interfaces diferentes com o mesmo nome curto: a do ExtensibleFilter tem
  // APENAS doFilter (sem init/destroy) e e' a que FilterDefinition.filter
  // aceita. Com a do servlet o kernel recusa a configuracao inteira com
  //   IllegalArgumentException: Can not set org.exoplatform.web.filter.Filter
  //   field FilterDefinition.filter to br.pmo.dlpsaida.exo.FiltroSaidaDlp
  // e a extensao nao carrega -- foi o que aconteceu no primeiro deploy.

  private static final Log LOG = ExoLogger.getLogger(FiltroSaidaDlp.class);

  /**
   * Caminhos por onde arquivo REALMENTE sai deste portal.
   *
   * <p>Nao sao suposicao: foram levantados no log de acesso do nginx desta
   * instalacao, olhando o que devolveu corpo de documento. Supor rota erra dos
   * dois lados -- deixa passar o que nao foi previsto e barra o que so' parece.
   * Cada entrada tem o canal a que corresponde, porque a politica decide
   * diferente para download e para link publico.
   */
  private static final String[][] ROTAS_SAIDA = {
      {"/portal/rest/v1/documents/content", "DOWNLOAD"},
      {"/rest/v1/documents/content", "DOWNLOAD"},
      {"/portal/rest/documents/download", "DOWNLOAD"},
      {"/portal/rest/v1/documents/download", "DOWNLOAD"},
      {"/portal/download", "DOWNLOAD"},
      {"/rest/jcr/", "DOWNLOAD"},
      {"/rest/private/jcr/", "DOWNLOAD"},
      {"/portal/rest/jcr/", "DOWNLOAD"},
      {"/portal/rest/private/jcr/", "DOWNLOAD"},
      {"/webdav/", "WEBDAV"},
      {"/portal/rest/wcmDriver/", "DOWNLOAD"},
      {"/portal/rest/contents/", "DOWNLOAD"},
      {"/content/rest/links/publicLinks", "LINK_PUBLICO"},
      {"/portal/rest/onlyoffice/editor/content", "EDITOR"},
      {"/rest/onlyoffice/editor/content", "EDITOR"},
      {"/portal/rest/v1/social/spaces/attachments", "COMPARTILHAMENTO_EXTERNO"},
      {"/portal/rest/wcmDriver/getFoldersAndFiles", "DOWNLOAD"},
  };

  private static final String[] EXTENSOES_IGNORADAS = {
      ".js", ".css", ".woff", ".woff2", ".ttf", ".eot", ".svg", ".ico",
      ".map", ".json"};

  private volatile Configuracao conf;

  private static final class Configuracao {
    final boolean ligado;
    final boolean aplicar;
    final boolean falhaAberta;
    final int tetoBytes;
    final ClienteDlp cliente;
    final List<String[]> rotas;

    Configuracao(boolean ligado, boolean aplicar, boolean falhaAberta,
                 int tetoBytes, ClienteDlp cliente, List<String[]> rotas) {
      this.ligado = ligado;
      this.aplicar = aplicar;
      this.falhaAberta = falhaAberta;
      this.tetoBytes = tetoBytes;
      this.cliente = cliente;
      this.rotas = rotas;
    }
  }

  private Configuracao configuracao() {
    Configuracao c = conf;
    if (c == null) {
      synchronized (this) {
        if (conf == null) {
          conf = montar();
        }
        c = conf;
      }
    }
    return c;
  }

  private Configuracao montar() {
    boolean ligado = !"false".equalsIgnoreCase(prop("exo.dlp.saida.ligado", "true"));
    // MODO OBSERVACAO por padrao. A primeira semana revela o volume real e as
    // rotas que faltaram; so' depois se liga a aplicacao. Ligar bloqueio de
    // saida no primeiro dia trava trabalho legitimo e a politica e' desligada
    // inteira -- que e' o pior desfecho possivel.
    boolean aplicar = "true".equalsIgnoreCase(prop("exo.dlp.saida.aplicar", "false"));
    boolean falhaAberta = "true".equalsIgnoreCase(prop("exo.dlp.falhaAberta", "false"));
    int teto = inteiro(prop("exo.dlp.tetoBytes", "33554432"), 33554432);
    String base = prop("exo.dlp.url", "http://dlp:8480");
    String token = prop("exo.dlp.token", "");
    int tempo = inteiro(prop("exo.dlp.tempoLimiteMs", "10000"), 10000);

    List<String[]> rotas = new ArrayList<>(Arrays.asList(ROTAS_SAIDA));
    String extras = prop("exo.dlp.saida.rotasExtras", "");
    for (String par : extras.split(",")) {
      String p = par.trim();
      if (p.isEmpty()) {
        continue;
      }
      int barra = p.indexOf('|');
      rotas.add(barra > 0
          ? new String[] {p.substring(0, barra), p.substring(barra + 1)}
          : new String[] {p, "DOWNLOAD"});
    }

    LOG.info("DLP saida: ligado={} aplicar={} falhaAberta={} teto={}B url={} rotas={}",
             ligado, aplicar, falhaAberta, teto, base, rotas.size());
    if (ligado && !aplicar) {
      LOG.info("DLP saida: MODO OBSERVACAO -- nada e' bloqueado; os incidentes "
               + "sao registrados para dimensionar antes de ligar "
               + "exo.dlp.saida.aplicar=true");
    }
    return new Configuracao(ligado, aplicar, falhaAberta, teto,
                            new ClienteDlp(base, token, tempo), rotas);
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    Configuracao c = configuracao();
    if (!c.ligado || !(req instanceof HttpServletRequest)) {
      chain.doFilter(req, res);
      return;
    }
    HttpServletRequest requisicao = (HttpServletRequest) req;
    HttpServletResponse resposta = (HttpServletResponse) res;

    String canal = canalDe(c, requisicao.getRequestURI());
    if (canal == null) {
      chain.doFilter(req, res);
      return;
    }

    RespostaCapturada captura = new RespostaCapturada(resposta, c.tetoBytes);
    chain.doFilter(req, captura);

    byte[] corpo = captura.corpo();
    if (corpo.length == 0) {
      captura.entregar();
      return;
    }

    ClienteDlp.Contexto ctx = new ClienteDlp.Contexto();
    ctx.canal = canal;
    ctx.usuario = requisicao.getRemoteUser() == null ? "" : requisicao.getRemoteUser();
    ctx.email = emailDe(ctx.usuario);
    ctx.ip = enderecoDe(requisicao);
    ctx.destino = cabecalho(requisicao, "Referer");
    ctx.nomeArquivo = nomeDe(requisicao.getRequestURI());
    ctx.recurso = requisicao.getRequestURI();
    // Em observacao o SERVICO tambem precisa se conter: sem isto ele retinha
    // no cofre e mandava e-mail enquanto o portal apenas observava.
    ctx.observacao = !c.aplicar;

    if (captura.estourouTeto()) {
      // Honestidade: o conteudo saiu SEM inspecao, e isso fica registrado.
      LOG.warn("DLP saida: {} acima do teto de {} bytes -- entregue SEM inspecao",
               ctx.recurso, c.tetoBytes);
      captura.entregar();
      return;
    }

    ClienteDlp.Veredito v;
    try {
      v = c.cliente.analisarArquivo(corpo, ctx);
    } catch (IOException e) {
      LOG.error("DLP saida: servico indisponivel ao avaliar {}", ctx.recurso, e);
      if (c.falhaAberta) {
        captura.entregar();
        return;
      }
      negar(resposta, "O servico de protecao de dados esta indisponivel. "
                      + "Por seguranca, a transferencia foi negada.", "", "", "", "");
      return;
    }

    if (!v.permitido && c.aplicar) {
      LOG.info("DLP saida: BLOQUEADO {} usuario={} regra={} acoes={} incidente={}",
               ctx.recurso, ctx.usuario, v.regraNome,
               String.join(",", v.acoesExecutadas), v.incidente);
      negar(resposta, v.mensagem, v.regraNome, v.incidente, v.orientacao,
            v.quarentena);
      return;
    }
    if (!v.permitido) {
      LOG.info("DLP saida: BLOQUEARIA (observacao) {} usuario={} regra={} "
               + "acoes={} incidente={}", ctx.recurso, ctx.usuario, v.regraNome,
               String.join(",", v.acoesExecutadas), v.incidente);
      captura.entregar();
      return;
    }

    // ENTREGA TRANSFORMADA. Este bloco e' a correcao do defeito registrado em
    // dlp/PENDENCIAS.md: o servico ja' devolvia a versao mascarada e o filtro
    // NUNCA a usava, entao no download o conteudo passava inteiro ou era
    // barrado -- MASCARAR e CRIPTOGRAFAR eram nome na politica e nada na tela.
    //
    // Em MODO OBSERVACAO a transformacao TAMBEM nao e' aplicada: observacao
    // quer dizer "nada muda para o usuario", e entregar um arquivo cifrado
    // durante a observacao seria mudar tudo.
    if (v.temTransformacao() && c.aplicar) {
      LOG.info("DLP saida: TRANSFORMADO {} usuario={} acoes={} incidente={} "
               + "({} -> {} bytes)", ctx.recurso, ctx.usuario,
               String.join(",", v.acoesExecutadas), v.incidente,
               corpo.length, v.conteudo.length);
      entregarTransformado(resposta, v);
      return;
    }
    if (v.temTransformacao()) {
      LOG.info("DLP saida: TRANSFORMARIA (observacao) {} usuario={} acoes={} "
               + "incidente={}", ctx.recurso, ctx.usuario,
               String.join(",", v.acoesExecutadas), v.incidente);
    }
    if (v.acoesNaoAplicaveis.length > 0) {
      // Acao que a regra pediu e o servico nao conseguiu cumprir. Fica no log
      // do portal tambem, e nao so' no incidente: quem opera o portal precisa
      // saber que a politica esta' pedindo algo impossivel para aquele formato.
      LOG.warn("DLP saida: acao NAO aplicavel em {} -> {}", ctx.recurso,
               String.join(" | ", v.acoesNaoAplicaveis));
    }
    captura.entregar();
  }

  /**
   * Entrega o conteudo que o servico devolveu no lugar do original.
   *
   * <p>Os cabecalhos sao reescritos: tipo, tamanho e nome do arquivo. Manter o
   * {@code Content-Disposition} antigo faria o navegador salvar um ZIP com
   * nome ".pdf", e o usuario concluiria que o arquivo veio corrompido.
   */
  private void entregarTransformado(HttpServletResponse res, ClienteDlp.Veredito v)
      throws IOException {
    if (res.isCommitted()) {
      LOG.error("DLP saida: resposta ja' comprometida; nao foi possivel "
                + "entregar a versao transformada do incidente {}", v.incidente);
      return;
    }
    res.reset();
    res.setStatus(HttpServletResponse.SC_OK);
    if (!v.mimeSaida.isEmpty()) {
      res.setContentType(v.mimeSaida);
    }
    if (!v.nomeSaida.isEmpty()) {
      res.setHeader("Content-Disposition",
                    "attachment; filename=\"" + v.nomeSaida.replace("\"", "") + "\"");
    }
    // Cabecalhos proprios: quem receber o arquivo consegue descobrir por que
    // ele veio diferente, sem abrir chamado.
    res.setHeader("X-DLP-Acao", String.join(",", v.acoesExecutadas));
    if (!v.incidente.isEmpty()) {
      res.setHeader("X-DLP-Incidente", v.incidente);
    }
    res.setContentLength(v.conteudo.length);
    res.getOutputStream().write(v.conteudo);
    res.getOutputStream().flush();
  }

  private void negar(HttpServletResponse res, String mensagem, String regra,
                     String incidente, String orientacao, String quarentena)
      throws IOException {
    if (res.isCommitted()) {
      return;
    }
    res.reset();
    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
    res.setContentType("text/html; charset=utf-8");
    String texto = mensagem == null || mensagem.isEmpty()
        ? "Transferencia bloqueada pela politica de protecao de dados."
        : mensagem;
    StringBuilder pagina = new StringBuilder(512);
    pagina.append("<!doctype html><meta charset=\"utf-8\">")
          .append("<title>Transferencia bloqueada</title>")
          .append("<body style=\"font-family:system-ui,sans-serif;")
          .append("max-width:40rem;margin:3rem auto;line-height:1.6\">")
          .append("<h1 style=\"font-size:1.4rem\">Transferencia bloqueada</h1><p>")
          .append(escaparHtml(texto)).append("</p>");
    if (!regra.isEmpty()) {
      pagina.append("<p style=\"color:#555;font-size:.9rem\">Regra: ")
            .append(escaparHtml(regra));
      if (!incidente.isEmpty()) {
        pagina.append("<br>Incidente: ").append(escaparHtml(incidente));
      }
      pagina.append("</p>");
    }
    if (!quarentena.isEmpty()) {
      // Dizer que o arquivo foi RETIDO, e nao apenas barrado, muda o que o
      // usuario faz a seguir: ele pede a liberacao em vez de tentar outro
      // caminho de saida.
      pagina.append("<p style=\"color:#555;font-size:.9rem\">O conteudo foi ")
            .append("retido para analise sob o numero ")
            .append(escaparHtml(quarentena))
            .append(" e pode ser liberado por um analista.</p>");
    }
    if (!orientacao.isEmpty()) {
      pagina.append("<p style=\"background:#f4f6f8;border-left:3px solid #888;")
            .append("padding:.75rem 1rem;font-size:.95rem\">")
            .append(escaparHtml(orientacao)).append("</p>");
    }
    pagina.append("<p style=\"color:#555;font-size:.9rem\">Se voce precisa deste ")
          .append("conteudo para o seu trabalho, procure a area de tecnologia ")
          .append("informando o numero do incidente.</p></body>");
    byte[] bytes = pagina.toString().getBytes(StandardCharsets.UTF_8);
    res.setContentLength(bytes.length);
    res.getOutputStream().write(bytes);
    res.getOutputStream().flush();
  }

  private String canalDe(Configuracao c, String uri) {
    if (uri == null) {
      return null;
    }
    String minusculo = uri.toLowerCase(Locale.ROOT);
    for (String ext : EXTENSOES_IGNORADAS) {
      if (minusculo.endsWith(ext)) {
        return null;
      }
    }
    for (String[] rota : c.rotas) {
      if (uri.startsWith(rota[0])) {
        return rota[1];
      }
    }
    return null;
  }

  private static String nomeDe(String uri) {
    if (uri == null) {
      return "";
    }
    int i = uri.lastIndexOf('/');
    return i >= 0 && i < uri.length() - 1 ? uri.substring(i + 1) : uri;
  }

  /**
   * E-mail do usuario, pelo cadastro do PORTAL.
   *
   * <p>O servico de DLP nao tem cadastro de pessoas. Sem esta consulta, a acao
   * NOTIFICAR_USUARIO so' conseguia montar {@code login@dominio-padrao} -- que
   * acerta quando o dominio coincide e erra em silencio quando nao coincide.
   * Quem sabe o endereco de verdade e' o portal, e e' ele quem informa.
   *
   * <p>Falha aqui NAO derruba a inspecao: devolve vazio e o servico registra o
   * aviso como "sem destinatario", que aparece no console. Perder o download
   * por causa da agenda de e-mail seria desproporcional.
   */
  private String emailDe(String usuario) {
    if (usuario == null || usuario.isEmpty()) {
      return "";
    }
    try {
      org.exoplatform.container.ExoContainer container =
          org.exoplatform.container.ExoContainerContext.getCurrentContainer();
      if (container == null) {
        return "";
      }
      org.exoplatform.services.organization.OrganizationService organizacao =
          container.getComponentInstanceOfType(
              org.exoplatform.services.organization.OrganizationService.class);
      if (organizacao == null) {
        return "";
      }
      org.exoplatform.services.organization.User u =
          organizacao.getUserHandler().findUserByName(usuario);
      return u == null || u.getEmail() == null ? "" : u.getEmail();
    } catch (Exception e) {                                 // NOSONAR
      LOG.debug("DLP saida: nao foi possivel resolver o e-mail de {}", usuario, e);
      return "";
    }
  }

  private static String enderecoDe(HttpServletRequest r) {
    String encaminhado = r.getHeader("X-Forwarded-For");
    if (encaminhado != null && !encaminhado.isEmpty()) {
      int virgula = encaminhado.indexOf(',');
      return virgula > 0 ? encaminhado.substring(0, virgula).trim()
                         : encaminhado.trim();
    }
    return r.getRemoteAddr() == null ? "" : r.getRemoteAddr();
  }

  private static String cabecalho(HttpServletRequest r, String nome) {
    String v = r.getHeader(nome);
    return v == null ? "" : v;
  }

  private static String escaparHtml(String v) {
    return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
  }

  /**
   * Ambiente tem precedencia sobre exo.properties, e valor que ainda comeca com
   * "${" e' descartado como nao-expandido. O eXo nao expande ${env.X} no
   * arquivo de propriedades: sem esta guarda, o token chegaria literalmente
   * como "${env.EXO_DLP_TOKEN:}" e o servico responderia 401 -- foi o que
   * aconteceu no primeiro deploy.
   */
  private static String prop(String chave, String padrao) {
    // exo.dlp.token -> EXO_DLP_TOKEN. A chave JA comeca com "exo.", entao
    // acrescentar outro prefixo produziria EXO_EXO_DLP_TOKEN, que nao existe.
    String variavel = chave.replace('.', '_').toUpperCase(Locale.ROOT);
    String v = System.getenv(variavel);
    if (v != null && !v.trim().isEmpty()) {
      return v.trim();
    }
    v = PropertyManager.getProperty(chave);
    if (v == null || v.trim().isEmpty() || v.trim().startsWith("${")) {
      return padrao;
    }
    return v.trim();
  }

  private static int inteiro(String v, int padrao) {
    try {
      return Integer.parseInt(v);
    } catch (NumberFormatException e) {
      return padrao;
    }
  }

  /** Acumula a resposta ate' o teto para poder inspecionar antes de entregar. */
  private static final class RespostaCapturada extends HttpServletResponseWrapper {
    private final ByteArrayOutputStream acumulador = new ByteArrayOutputStream();
    private final int teto;
    private boolean estourou;
    private ServletOutputStream fluxo;
    private PrintWriter escritor;

    RespostaCapturada(HttpServletResponse original, int teto) {
      super(original);
      this.teto = teto;
    }

    byte[] corpo() throws IOException {
      if (escritor != null) {
        escritor.flush();
      }
      if (fluxo != null) {
        fluxo.flush();
      }
      return acumulador.toByteArray();
    }

    boolean estourouTeto() {
      return estourou;
    }

    void entregar() throws IOException {
      byte[] dados = corpo();
      if (dados.length == 0) {
        return;
      }
      HttpServletResponse original = (HttpServletResponse) getResponse();
      if (!original.isCommitted()) {
        original.setContentLength(dados.length);
      }
      original.getOutputStream().write(dados);
      original.getOutputStream().flush();
    }

    @Override
    public ServletOutputStream getOutputStream() {
      if (fluxo == null) {
        fluxo = new ServletOutputStream() {
          @Override
          public void write(int b) {
            if (acumulador.size() < teto) {
              acumulador.write(b);
            } else {
              estourou = true;
            }
          }

          @Override
          public void write(byte[] b, int off, int len) {
            if (acumulador.size() + len <= teto) {
              acumulador.write(b, off, len);
            } else {
              estourou = true;
            }
          }

          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setWriteListener(WriteListener l) {
            // Escrita sincrona: nao ha' modo assincrono aqui.
          }
        };
      }
      return fluxo;
    }

    @Override
    public PrintWriter getWriter() {
      if (escritor == null) {
        escritor = new PrintWriter(new java.io.OutputStreamWriter(
            acumulador, StandardCharsets.UTF_8), true);
      }
      return escritor;
    }

    @Override
    public void flushBuffer() {
      // NAO propaga: comprometer a resposta antes da decisao tornaria o
      // bloqueio impossivel (nao da' para retirar byte que ja' saiu).
    }

    @Override
    public void setContentLength(int len) {
      // O tamanho final e' o do corpo entregue, definido em entregar().
    }

    @Override
    public void setContentLengthLong(long len) {
      // idem
    }
  }
}
