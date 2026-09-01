package br.pmo.dlpsaida.exo;

import br.pmo.dlpsaida.ClienteDlp;
import jakarta.annotation.security.RolesAllowed;
// NOTA DE VERSAO, conferida na imagem em 2026-08-31 e nao suposta:
//   * as anotacoes de REST sao javax.ws.rs (JSR-311, de jsr311-api.jar) --
//     e' o que o proprio DlpItemRestServices da eXo usa;
//   * RolesAllowed ja' e' jakarta.annotation.security (annotations-api.jar).
// Os dois convivem: a migracao da eXo para Tomcat 10 moveu servlet e
// annotations para jakarta e deixou a camada REST propria em javax. Trocar um
// pelo outro por simetria quebra o deploy do webapp inteiro.
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.rest.resource.ResourceContainer;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

/**
 * REST do console de DLP, DENTRO do portal.
 *
 * <p><b>POR QUE O PORTAL INTERMEDIA EM VEZ DE O NAVEGADOR FALAR DIRETO COM O
 * SERVICO:</b> tres motivos, e nenhum e' estetico.
 * <ol>
 *   <li>O token da API nunca chega ao navegador. Se o console chamasse o
 *       servico diretamente, o token estaria no JavaScript -- ou seja, com
 *       qualquer usuario autenticado.</li>
 *   <li>O servico de DLP nao tem porta publicada; ele so' existe na rede
 *       interna do compose. E' o desenho certo para algo que le todo arquivo
 *       que passa.</li>
 *   <li>A autorizacao e' a do portal. Quem nao esta' em
 *       {@code /platform/administrators} nao passa daqui.</li>
 * </ol>
 *
 * <p>Nao ha' painel externo: esta classe e' o que faz a gestao do DLP acontecer
 * dentro do eXo, como qualquer outra integracao do projeto.
 */
// ROTA /dlp-pmo, e NAO /dlp: o add-on nativo da eXo ja' publica
// org.exoplatform.dlp.rest.DlpRestServices no padrao "/dlp(/.*)?". Registrar
// outra classe no mesmo padrao faz o ResourceBinder lancar
// ResourcePublicationException -- e isso NAO e' um aviso: aborta a criacao do
// PortalContainer inteiro e o portal nao sobe. Medido em 2026-08-31.
@Path("/dlp-pmo")
@Produces(MediaType.APPLICATION_JSON)
public class ConsoleDlpRest implements ResourceContainer {

  private static final Log LOG = ExoLogger.getLogger(ConsoleDlpRest.class);

  private final ClienteDlp cliente;

  public ConsoleDlpRest() {
    String base = prop("exo.dlp.url", "http://dlp:8480");
    String token = prop("exo.dlp.token", "");
    int tempo = 15000;
    this.cliente = new ClienteDlp(base, token, tempo);
    LOG.info("Console de DLP publicado em /rest/dlp-pmo -> {}", base);
  }

  @GET
  @Path("/saude")
  @RolesAllowed("administrators")
  public Response saude() {
    return repassar("GET", "/saude", null);
  }

  @GET
  @Path("/painel")
  @RolesAllowed("administrators")
  public Response painel(@QueryParam("dias") String dias) {
    return repassar("GET", "/painel?dias=" + numero(dias, "30"), null);
  }

  @GET
  @Path("/incidentes")
  @RolesAllowed("administrators")
  public Response incidentes(@Context UriInfo info) {
    return repassar("GET", "/incidentes?" + consulta(info), null);
  }

  @GET
  @Path("/incidentes/{id}")
  @RolesAllowed("administrators")
  public Response incidente(@PathParam("id") String id) {
    return repassar("GET", "/incidentes/" + seguro(id), null);
  }

  @POST
  @Path("/incidentes/{id}/estado")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response estado(@PathParam("id") String id, String corpo) {
    return agir("POST", "/incidentes/" + seguro(id) + "/estado", corpo);
  }

  @POST
  @Path("/incidentes/{id}/atribuir")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response atribuir(@PathParam("id") String id, String corpo) {
    return agir("POST", "/incidentes/" + seguro(id) + "/atribuir", corpo);
  }

  @POST
  @Path("/incidentes/{id}/anotar")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response anotar(@PathParam("id") String id, String corpo) {
    return agir("POST", "/incidentes/" + seguro(id) + "/anotar", corpo);
  }

  @POST
  @Path("/incidentes/lote")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response lote(String corpo) {
    return agir("POST", "/incidentes/lote", corpo);
  }

  @GET
  @Path("/politica")
  @RolesAllowed("administrators")
  public Response lerPolitica() {
    return repassar("GET", "/politica", null);
  }

  @PUT
  @Path("/politica")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response gravarPolitica(String corpo) {
    return agir("PUT", "/politica", corpo);
  }

  @POST
  @Path("/indices/edm/{nome}")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response edm(@PathParam("nome") String nome, String corpo) {
    return agir("POST", "/indices/edm/" + seguro(nome), corpo);
  }

  @POST
  @Path("/indices/idm/{nome}")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response idm(@PathParam("nome") String nome, String corpo) {
    return agir("POST", "/indices/idm/" + seguro(nome), corpo);
  }

  @POST
  @Path("/modelo/treinar")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response treinar(String corpo) {
    return agir("POST", "/modelo/treinar", corpo);
  }

  @GET
  @Path("/agentes")
  @RolesAllowed("administrators")
  public Response agentes() {
    return repassar("GET", "/agentes", null);
  }

  // ===========================================================================
  // ROTAS QUE ANTES SO' EXISTIAM DENTRO DO PORTLET
  //
  // Ate' 2026-09-01 estas oito secoes eram lidas pelo proprio portlet, no
  // servidor, e transformadas em HTML por ele. Com o console no padrao da
  // plataforma (GenericDispatchedViewPortlet + Vue), quem le e' o navegador --
  // e o navegador so' alcanca o servico de DLP por aqui. Sem estas rotas, oito
  // das onze secoes do console ficariam sem fonte de dados.
  //
  // NENHUM VERBO DESTRUTIVO. O servico expoe DELETE para indice e dicionario;
  // esta classe NAO os republica. Desativar um indice se faz por
  // /indices/{tipo}/{nome}/estado, que preserva o registro e a trilha. E' a
  // restricao do projeto aplicada onde ela vale: na porta que o operador
  // alcanca com o mouse.
  // ===========================================================================

  @GET
  @Path("/revisao")
  @RolesAllowed("administrators")
  public Response revisao(@QueryParam("limite") String limite) {
    return repassar("GET", "/revisao?limite=" + numero(limite, "100"), null);
  }

  @POST
  @Path("/revisao/{id}/aprovar")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response aprovarRevisao(@PathParam("id") String id, String corpo) {
    return agir("POST", "/revisao/" + seguro(id) + "/aprovar", corpo);
  }

  @POST
  @Path("/revisao/{id}/reprovar")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response reprovarRevisao(@PathParam("id") String id, String corpo) {
    return agir("POST", "/revisao/" + seguro(id) + "/reprovar", corpo);
  }

  @GET
  @Path("/quarentena")
  @RolesAllowed("administrators")
  public Response quarentena(@QueryParam("limite") String limite) {
    return repassar("GET", "/quarentena?limite=" + numero(limite, "100"), null);
  }

  @GET
  @Path("/quarentena/{id}")
  @RolesAllowed("administrators")
  public Response itemRetido(@PathParam("id") String id) {
    return repassar("GET", "/quarentena/" + seguro(id), null);
  }

  @POST
  @Path("/quarentena/{id}/liberar")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response liberarRetido(@PathParam("id") String id, String corpo) {
    return agir("POST", "/quarentena/" + seguro(id) + "/liberar", corpo);
  }

  @POST
  @Path("/quarentena/{id}/descartar")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response descartarRetido(@PathParam("id") String id, String corpo) {
    return agir("POST", "/quarentena/" + seguro(id) + "/descartar", corpo);
  }

  @GET
  @Path("/liberacoes")
  @RolesAllowed("administrators")
  public Response liberacoes(@QueryParam("limite") String limite) {
    return repassar("GET", "/liberacoes?limite=" + numero(limite, "100"), null);
  }

  @POST
  @Path("/liberacoes/{id}/revogar")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response revogarLiberacao(@PathParam("id") String id, String corpo) {
    return agir("POST", "/liberacoes/" + seguro(id) + "/revogar", corpo);
  }

  @GET
  @Path("/notificacoes")
  @RolesAllowed("administrators")
  public Response notificacoes(@QueryParam("limite") String limite) {
    return repassar("GET", "/notificacoes?limite=" + numero(limite, "100"), null);
  }

  @POST
  @Path("/notificacoes/{id}/reenviar")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response reenviarAviso(@PathParam("id") String id, String corpo) {
    return agir("POST", "/notificacoes/" + seguro(id) + "/reenviar", corpo);
  }

  @GET
  @Path("/politica/modelos")
  @RolesAllowed("administrators")
  public Response modelosDePolitica() {
    return repassar("GET", "/politica/modelos", null);
  }

  @GET
  @Path("/indices")
  @RolesAllowed("administrators")
  public Response indices() {
    return repassar("GET", "/indices", null);
  }

  @GET
  @Path("/indices/{tipo}/{nome}")
  @RolesAllowed("administrators")
  public Response indice(@PathParam("tipo") String tipo,
                         @PathParam("nome") String nome) {
    return repassar("GET", "/indices/" + seguro(tipo) + "/" + seguro(nome), null);
  }

  @POST
  @Path("/indices/{tipo}/{nome}/estado")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response estadoDoIndice(@PathParam("tipo") String tipo,
                                 @PathParam("nome") String nome, String corpo) {
    return agir("POST",
                    "/indices/" + seguro(tipo) + "/" + seguro(nome) + "/estado",
                    corpo);
  }

  @GET
  @Path("/dicionarios")
  @RolesAllowed("administrators")
  public Response dicionarios() {
    return repassar("GET", "/dicionarios", null);
  }

  @PUT
  @Path("/dicionarios/{nome}")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response gravarDicionario(@PathParam("nome") String nome, String corpo) {
    return agir("PUT", "/dicionarios/" + seguro(nome), corpo);
  }

  @GET
  @Path("/descoberta/origens")
  @RolesAllowed("administrators")
  public Response origens() {
    return repassar("GET", "/descoberta/origens", null);
  }

  @GET
  @Path("/descoberta/varreduras")
  @RolesAllowed("administrators")
  public Response varreduras(@QueryParam("limite") String limite) {
    return repassar("GET",
                    "/descoberta/varreduras?limite=" + numero(limite, "50"), null);
  }

  @POST
  @Path("/descoberta/varreduras")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response iniciarVarredura(String corpo) {
    return agir("POST", "/descoberta/varreduras", corpo);
  }

  @POST
  @Path("/descoberta/varreduras/{id}/cancelar")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response cancelarVarredura(@PathParam("id") String id, String corpo) {
    return agir("POST",
                    "/descoberta/varreduras/" + seguro(id) + "/cancelar", corpo);
  }

  @POST
  @Path("/agentes/registrar")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("administrators")
  public Response registrarAgente(String corpo) {
    return agir("POST", "/agentes/registrar", corpo);
  }

  @GET
  @Path("/certificados")
  @RolesAllowed("administrators")
  public Response certificados() {
    return repassar("GET", "/certificados", null);
  }

  @GET
  @Path("/auditoria")
  @RolesAllowed("administrators")
  public Response auditoria(@QueryParam("limite") String limite) {
    return repassar("GET", "/auditoria?limite=" + numero(limite, "100"), null);
  }

  @GET
  @Path("/relatorios/conformidade/{norma}")
  @RolesAllowed("administrators")
  public Response conformidade(@PathParam("norma") String norma,
                               @QueryParam("dias") String dias,
                               @QueryParam("formato") String formato) {
    String caminho = "/relatorios/conformidade/" + seguro(norma)
        + "?dias=" + numero(dias, "90")
        + ("html".equals(formato) ? "&formato=html" : "");
    return repassar("GET", caminho, null,
                    "html".equals(formato) ? MediaType.TEXT_HTML : MediaType.APPLICATION_JSON);
  }

  @GET
  @Path("/relatorios/incidentes.csv")
  @Produces("text/csv")
  @RolesAllowed("administrators")
  public Response csv(@Context UriInfo info) {
    return repassar("GET", "/relatorios/incidentes.csv?" + consulta(info), null,
                    "text/csv");
  }

  /**
   * Devolve o conteudo ORIGINAL retido em quarentena.
   *
   * <p>Existe aqui, e nao como ResourceURL do portlet, por um motivo medido:
   * nesta instalacao o eXo NAO entrega ao portlet os parametros codificados em
   * PortletURL (medido em 2026-09-01: o portlet recebia
   * {@code parametros=[]} mesmo com a URL carregando o estado). Um link por
   * linha de tabela precisa levar o identificador, e uma rota REST com o id no
   * CAMINHO leva. A autorizacao e' a mesma das demais rotas desta classe.
   */
  @GET
  @Path("/quarentena/{id}/conteudo")
  @Produces(MediaType.APPLICATION_OCTET_STREAM)
  @RolesAllowed("administrators")
  public Response conteudoRetido(@PathParam("id") String id) {
    try {
      byte[] dados = cliente.baixar("/quarentena/" + seguro(id) + "/conteudo");
      String nome = nomeDoItem(id, "retido-" + id);
      return Response.ok(dados, MediaType.APPLICATION_OCTET_STREAM)
          .header("Content-Disposition",
                  "attachment; filename=\"" + nome.replace("\"", "") + "\"")
          .header("X-Content-Type-Options", "nosniff")
          .build();
    } catch (IOException e) {
      LOG.error("Console de DLP: falha ao restaurar o item {}", id, e);
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .entity("{\"erro\":\"nao foi possivel restaurar o conteudo retido\"}")
          .type(MediaType.APPLICATION_JSON).build();
    }
  }

  /** Nome do arquivo original, para o navegador salvar com um nome util. */
  private String nomeDoItem(String id, String padrao) {
    try {
      String bruto = cliente.repassar("GET", "/quarentena/" + seguro(id), null);
      String nome = ClienteDlp.Veredito.textoDe(bruto, "nome_arquivo");
      return nome.isEmpty() ? padrao : nome;
    } catch (IOException e) {
      LOG.debug("Console de DLP: nome do item {} indisponivel", id, e);
      return padrao;
    }
  }


  // ===========================================================================
  // AUTORIA
  //
  // O servico de DLP exige `autor` no corpo de toda acao que muda estado, e o
  // recusa sem ele. Ate' 2026-09-01 quem montava esse corpo era o portlet, no
  // servidor. Com a tela em Vue, o corpo passa a vir do NAVEGADOR — e um corpo
  // vindo do navegador nao pode dizer quem e' o autor: qualquer administrador
  // poderia assinar a trilha de auditoria com o nome de outro.
  //
  // Por isso o autor NAO e' lido do corpo: e' carimbado aqui, a partir da
  // sessao do portal, sobrescrevendo o que o navegador tenha mandado. A trilha
  // passa a registrar quem o portal autenticou, e nao quem o cliente alegou.
  // ===========================================================================

  /** Quem o PORTAL autenticou. Nunca o que o corpo da requisicao alega. */
  private static String autorAutenticado() {
    ConversationState estado = ConversationState.getCurrent();
    if (estado == null) {
      return "";
    }
    Identity identidade = estado.getIdentity();
    return identidade == null || identidade.getUserId() == null
        ? "" : identidade.getUserId();
  }

  /**
   * Devolve o corpo com {@code autor} carimbado, sobrescrevendo o que veio.
   *
   * <p>Escrito a mao e nao com biblioteca de JSON de proposito: o corpo e'
   * repassado como texto opaco ao servico, e converter para objeto e de volta
   * reordenaria campos e perderia precisao numerica sem ganho nenhum. O unico
   * campo que esta classe precisa tocar e' este.
   */
  private static String comAutor(String corpo) {
    String autor = autorAutenticado().replace("\\", "\\\\").replace("\"", "\\\"");
    String par = "\"autor\":\"" + autor + "\"";
    if (corpo == null || corpo.trim().isEmpty()) {
      return "{" + par + "}";
    }
    String limpo = corpo.trim();
    if (!limpo.startsWith("{")) {
      // Corpo que nao e' objeto JSON nao tem onde receber o autor. Deixa
      // passar: o servico recusara' com a propria mensagem, que e' mais util
      // do que um erro inventado aqui.
      return corpo;
    }
    // Remove um `autor` que o navegador tenha mandado, para nao ficarem dois.
    String miolo = limpo.substring(1).trim();
    if (miolo.startsWith("}")) {
      return "{" + par + "}";
    }
    return "{" + par + "," + miolo;
  }

  /** Repasse de acao: mesmo caminho de sempre, com o autor carimbado. */
  private Response agir(String metodo, String caminho, String corpo) {
    if (autorAutenticado().isEmpty()) {
      LOG.warn("Console de DLP: acao {} {} sem identidade na sessao; recusada",
               metodo, caminho);
      return Response.status(Response.Status.FORBIDDEN)
          .entity("{\"erro\":\"sessao sem identidade\"}")
          .type(MediaType.APPLICATION_JSON).build();
    }
    return repassar(metodo, caminho, comAutor(corpo));
  }

  private Response repassar(String metodo, String caminho, String corpo) {
    return repassar(metodo, caminho, corpo, MediaType.APPLICATION_JSON);
  }

  private Response repassar(String metodo, String caminho, String corpo,
                            String tipo) {
    try {
      String resposta = cliente.repassar(metodo, caminho, corpo);
      return Response.ok(resposta, tipo).build();
    } catch (IOException e) {
      LOG.error("Console de DLP: falha ao falar com o servico em {}", caminho, e);
      // A mensagem diz o que fazer, nao apenas que falhou.
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .entity("{\"erro\":\"servico de DLP indisponivel\","
                  + "\"detalhe\":\"confira o container exo-dlp e a propriedade "
                  + "exo.dlp.url\"}")
          .type(MediaType.APPLICATION_JSON).build();
    }
  }

  /** Consulta repassada campo a campo: nada de string do usuario direto na URL. */
  private static String consulta(UriInfo info) {
    StringBuilder b = new StringBuilder();
    Map<String, List<String>> parametros = info.getQueryParameters();
    for (Map.Entry<String, List<String>> e : parametros.entrySet()) {
      for (String v : e.getValue()) {
        if (b.length() > 0) {
          b.append('&');
        }
        b.append(seguro(e.getKey())).append('=').append(seguro(v));
      }
    }
    return b.toString();
  }

  /** Codifica para URL. Impede que valor do usuario mude a rota chamada. */
  private static String seguro(String v) {
    if (v == null) {
      return "";
    }
    try {
      return java.net.URLEncoder.encode(v, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      return "";
    }
  }

  private static String numero(String v, String padrao) {
    if (v == null || v.isEmpty()) {
      return padrao;
    }
    for (int i = 0; i < v.length(); i++) {
      if (!Character.isDigit(v.charAt(i))) {
        return padrao;
      }
    }
    return v;
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
}
