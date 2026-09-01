package br.pmo.dlpconsole.exo;

import br.pmo.dlpconsole.Html;
import br.pmo.dlpconsole.Json;
import br.pmo.dlpconsole.Pagina;
import br.pmo.dlpconsole.Tela;
import br.pmo.dlpsaida.ClienteDlp;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.GenericPortlet;
import javax.portlet.PortletException;
import javax.portlet.PortletSession;
import javax.portlet.PortletURL;
import javax.portlet.RenderRequest;
import javax.portlet.RenderResponse;
import javax.portlet.ResourceRequest;
import javax.portlet.ResourceResponse;
import javax.portlet.ResourceURL;
import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

/**
 * O console de DLP DENTRO do portal.
 *
 * <p><b>O QUE ESTA CLASSE ENCERRA.</b> Ate' 2026-08-31 a administracao do DLP
 * so' era possivel por chamada REST na mao. A API estava completa e provada; a
 * TELA nao existia. Era o item mais visivel de {@code dlp/PENDENCIAS.md}, e a
 * consequencia pratica e' que politica, incidente, quarentena e fila de
 * revisao ficavam inalcancaveis para quem administra o portal.
 *
 * <p><b>O PORTAL INTERMEDIA; O NAVEGADOR NAO FALA COM O SERVICO.</b> Todo dado
 * desta tela e' buscado por esta classe, no servidor, com o token da API. O
 * token nunca chega ao navegador e o servico de DLP nao tem porta publicada.
 *
 * <p><b>SEGURANCA EM DUAS CAMADAS</b>, pela mesma razao do painel de
 * conformidade deste projeto:
 * <ol>
 *   <li>a pagina declara {@code access-permissions *:/platform/administrators}
 *       em {@code pages.xml};</li>
 *   <li>esta classe confere a associacao ao grupo em CADA render, em CADA acao
 *       e em CADA download. A primeira camada e' declarativa e editavel pela
 *       propria interface do portal; a segunda nao. E a checagem na acao NAO e'
 *       redundante: o POST e' um pedido separado e nao passa pelo render.</li>
 * </ol>
 *
 * <p><b>AUTOR DE TODA ACAO.</b> Nenhuma operacao que muda estado e' enviada
 * sem o login de quem clicou. A API recusa {@code autor} vazio -- de proposito:
 * liberacao de quarentena sem autor e' liberacao que ninguem consegue auditar.
 */
public class PortletConsoleDlp extends GenericPortlet {

  private static final Log LOG = ExoLogger.getExoLogger(PortletConsoleDlp.class);

  private static final String PADRAO_GRUPO = "/platform/administrators";

  /** Nomes dos filtros da aba de incidentes, mantidos como parametros de render. */
  private static final String[] FILTROS =
      {"busca", "severidade", "canal", "usuario", "estado"};

  /**
   * Marcador substituido pelo identificador nos modelos de URL.
   *
   * <p>Precisa sobreviver a codificacao da {@code PortletURL} sem virar outra
   * coisa: por isso e' composto so' de letras e sublinhados.
   */
  private static final String MARCADOR = "__ID__";
  private static final String SESSAO_MENSAGEM = "pmo.dlp.mensagem";
  private static final String SESSAO_ERRO = "pmo.dlp.erro";

  private String grupoAdministradores;

  /**
   * Liga o registro do que chega em cada render.
   *
   * <p>Existe porque "a aba nao troca" e um sintoma que pode vir de tres
   * lugares diferentes (a URL nao carrega, o contentor nao entrega, o codigo
   * descarta) e o log e o unico jeito de separar os tres sem adivinhar.
   */
  private boolean diagnostico;
  private ClienteDlp cliente;
  private int limiteLista;

  @Override
  public void init() throws PortletException {
    grupoAdministradores = texto("grupo.administradores", PADRAO_GRUPO);
    limiteLista = inteiro("limite.lista", 100);
    diagnostico = "true".equalsIgnoreCase(texto("diagnostico", "false"));
    String base = ClienteDlp.configuracao("EXO_DLP_URL", "exo.dlp.url",
                                          "http://dlp:8480");
    String token = ClienteDlp.configuracao("EXO_DLP_TOKEN", "exo.dlp.token", "");
    cliente = new ClienteDlp(base, token,
                             inteiro("tempo.limite.ms", 20000));
    if (token.isEmpty()) {
      // Aviso no arranque, e nao na primeira tela aberta: log de arranque e'
      // lido; tela que responde 401 vira chamado.
      LOG.warn("Console de DLP: token vazio. Defina EXO_DLP_TOKEN no ambiente "
               + "do exo-app -- e' o mesmo valor que o container exo-dlp recebe.");
    }
    LOG.info("Console de DLP iniciado: grupo={} servico={}", grupoAdministradores,
             base);
  }

  // ===========================================================================
  // Render
  // ===========================================================================
  @Override
  protected void doView(RenderRequest pedido, RenderResponse resposta)
      throws PortletException, IOException {
    resposta.setContentType("text/html; charset=UTF-8");
    PrintWriter escritor = resposta.getWriter();
    if (!ehAdministrador()) {
      escritor.write(Pagina.acessoNegado(grupoAdministradores));
      return;
    }

    Tela tela = new Tela();
    tela.espacoNomes = resposta.getNamespace();
    tela.usuario = usuarioAtual();
    tela.aba = aba(pedido.getParameter("aba"));
    // Registro de diagnostico: sem ele, "a aba nao troca" e um sintoma sem
    // causa. Diz o que CHEGOU ao portlet, e nao o que a URL parecia carregar.
    if (LOG.isDebugEnabled() || diagnostico) {
      StringBuilder nomes = new StringBuilder();
      java.util.Enumeration<String> e = pedido.getParameterNames();
      while (e.hasMoreElements()) {
        String n = e.nextElement();
        nomes.append(nomes.length() == 0 ? "" : ", ").append(n).append('=')
             .append(pedido.getParameter(n));
      }
      LOG.info("Console de DLP: render aba={} parametros=[{}]", tela.aba, nomes);
    }
    tela.detalhe = valor(pedido.getParameter("detalhe"));
    tela.urlAcao = resposta.createActionURL().toString();
    tela.urlRecurso = resposta.createResourceURL().toString();
    for (Pagina.Aba a : Pagina.ABAS) {
      PortletURL url = resposta.createRenderURL();
      url.setParameter("aba", a.codigo);
      tela.urlsAbas.put(a.codigo, url.toString());
    }
    for (String nome : FILTROS) {
      String v = valor(pedido.getParameter(nome));
      if (!v.isEmpty()) {
        tela.filtros.put(nome, v);
      }
    }

    // MODELOS de URL. O marcador e' codificado pela propria PortletURL e
    // depois substituido pelo identificador -- e' o unico jeito de um link
    // por linha de tabela chegar ao portlet com o parametro. Concatenar
    // "&detalhe=..." no fim de uma PortletURL nao funciona: o contentor
    // entrega apenas o que foi codificado, e o detalhe nunca abria.
    PortletURL detalhe = resposta.createRenderURL();
    detalhe.setParameter("aba", "incidentes");
    detalhe.setParameter("detalhe", MARCADOR);
    tela.urlDetalhe = detalhe.toString();

    ResourceURL conteudo = resposta.createResourceURL();
    conteudo.setParameter("pmoRecurso", "quarentena");
    conteudo.setParameter("pmoId", MARCADOR);
    tela.urlQuarentenaConteudo = conteudo.toString();

    ResourceURL csv = resposta.createResourceURL();
    csv.setParameter("pmoRecurso", "csv");
    for (Map.Entry<String, String> f : tela.filtros.entrySet()) {
      csv.setParameter(f.getKey(), f.getValue());
    }
    tela.urlCsv = csv.toString();
    PortletSession sessao = pedido.getPortletSession();
    tela.mensagem = consumir(sessao, SESSAO_MENSAGEM);
    tela.erro = consumir(sessao, SESSAO_ERRO);

    escritor.write(Pagina.pagina(tela, carregar(tela)));
  }

  /**
   * Busca no servico apenas o que a aba corrente precisa.
   *
   * <p>Carregar tudo em toda tela custaria uma dezena de chamadas por clique.
   * Falha de UMA chamada nao derruba a tela: a secao correspondente aparece
   * vazia com o motivo, o que e' mais util do que uma pagina de erro.
   */
  private Map<String, Object> carregar(Tela tela) {
    Map<String, Object> dados = new LinkedHashMap<>();
    switch (tela.aba) {
      case "incidentes":
        if (!tela.detalhe.isEmpty()) {
          dados.put("incidente",
                    obter("/incidentes/" + Html.codificar(tela.detalhe)));
        } else {
          dados.put("incidentes", obter("/incidentes?limite=" + limiteLista
                                        + "&" + Html.consulta(tela.filtros)));
        }
        break;
      case "revisao":
        dados.put("revisao", obter("/revisao?limite=" + limiteLista));
        break;
      case "quarentena":
        dados.put("quarentena", obter("/quarentena?limite=" + limiteLista));
        dados.put("liberacoes", obter("/liberacoes?limite=" + limiteLista));
        break;
      case "politica":
        try {
          // Aqui a resposta CRUA importa: alem da tabela, o texto integral
          // alimenta o editor. Falha nao derruba a tela -- a aba aparece com o
          // aviso de servico fora do ar, que e' o que o operador precisa ver.
          String politica = bruto("/politica");
          dados.put("politica", Json.objeto(politica));
          dados.put("politica_bruta", Json.formatar(politica));
        } catch (IOException e) {
          LOG.warn("Console de DLP: /politica indisponivel: {}", e.getMessage());
        }
        break;
      case "indices":
        dados.put("indices", obter("/indices"));
        break;
      case "dicionarios":
        dados.put("dicionarios", obter("/dicionarios"));
        break;
      case "descoberta":
        dados.put("origens", obter("/descoberta/origens"));
        dados.put("varreduras", obter("/descoberta/varreduras?limite=50"));
        break;
      case "notificacoes":
        dados.put("notificacoes", obter("/notificacoes?limite=" + limiteLista));
        break;
      case "agentes":
        dados.put("agentes", obter("/agentes"));
        break;
      case "auditoria":
        dados.put("auditoria", obter("/auditoria?limite=" + limiteLista));
        break;
      default:
        dados.put("saude", obter("/saude"));
        dados.put("painel", obter("/painel?dias=30"));
    }
    return dados;
  }

  // ===========================================================================
  // Acoes
  // ===========================================================================
  @Override
  public void processAction(ActionRequest pedido, ActionResponse resposta)
      throws PortletException, IOException {
    PortletSession sessao = pedido.getPortletSession();
    if (!ehAdministrador()) {
      // O POST e' um pedido proprio e nao passa pelo doView. Sem esta linha, a
      // liberacao de quarentena ficaria aberta a quem nao pode ver a tela.
      LOG.warn("Console de DLP: acao recusada, usuario fora de {}",
               grupoAdministradores);
      sessao.setAttribute(SESSAO_ERRO, "Acao recusada: e' exigida participacao "
                                       + "no grupo " + grupoAdministradores);
      return;
    }
    String acao = valor(pedido.getParameter("pmoAcao"));
    String aba = aba(pedido.getParameter("pmoAba"));
    resposta.setRenderParameter("aba", aba);
    String autor = usuarioAtual();

    // Os filtros sao PARAMETROS DE RENDER, e nao estado de sessao: assim o
    // botao de voltar do navegador e um link copiado levam a mesma listagem.
    if ("filtrar".equals(acao)) {
      for (String nome : FILTROS) {
        String v = valor(pedido.getParameter("pmo" + maiuscula(nome)));
        if (!v.isEmpty()) {
          resposta.setRenderParameter(nome, v);
        }
      }
      return;
    }
    if ("limpar_filtros".equals(acao)) {
      return;
    }
    // Uma acao executada na aba de incidentes preserva os filtros correntes,
    // senao o analista perde a busca a cada anotacao.
    for (String nome : FILTROS) {
      String v = valor(pedido.getParameter(nome));
      if (!v.isEmpty()) {
        resposta.setRenderParameter(nome, v);
      }
    }
    try {
      String retorno = executar(acao, autor, pedido);
      sessao.setAttribute(SESSAO_MENSAGEM, retorno);
      LOG.info("Console de DLP: {} por {} -> {}", acao, autor, retorno);
    } catch (Exception e) {                                 // NOSONAR
      LOG.error("Console de DLP: falha na acao {} pedida por {}", acao, autor, e);
      sessao.setAttribute(SESSAO_ERRO, mensagemDe(e));
    }
  }

  private String executar(String acao, String autor, ActionRequest pedido)
      throws IOException {
    switch (acao) {
      case "estado":
        return enviar("POST", "/incidentes/" + id(pedido) + "/estado",
                      corpo(autor, "estado", pedido.getParameter("pmoEstado"),
                            "detalhe", pedido.getParameter("pmoDetalhe")),
                      "Estado do incidente alterado.");
      case "atribuir":
        return enviar("POST", "/incidentes/" + id(pedido) + "/atribuir",
                      corpo(autor, "responsavel",
                            pedido.getParameter("pmoResponsavel")),
                      "Incidente atribuido.");
      case "anotar":
        return enviar("POST", "/incidentes/" + id(pedido) + "/anotar",
                      corpo(autor, "texto", pedido.getParameter("pmoTexto")),
                      "Anotacao registrada.");
      case "aprovar":
        return enviar("POST", "/revisao/" + id(pedido) + "/aprovar",
                      corpo(autor, "justificativa",
                            pedido.getParameter("pmoJustificativa"),
                            "horas", numero(pedido.getParameter("pmoHoras"), "24"),
                            "teto_usos", numero(pedido.getParameter("pmoUsos"), "1")),
                      "Revisao aprovada: a transferencia foi liberada para o "
                      + "usuario, com prazo e contagem de usos.");
      case "reprovar":
        return enviar("POST", "/revisao/" + id(pedido) + "/reprovar",
                      corpo(autor, "justificativa",
                            pedido.getParameter("pmoJustificativa")),
                      "Revisao reprovada; incidente confirmado.");
      case "liberar_quarentena":
        return enviar("POST", "/quarentena/" + id(pedido) + "/liberar",
                      corpo(autor, "justificativa",
                            pedido.getParameter("pmoJustificativa"),
                            "horas", numero(pedido.getParameter("pmoHoras"), "24")),
                      "Item liberado e transferencia autorizada.");
      case "descartar_quarentena":
        return enviar("POST", "/quarentena/" + id(pedido) + "/descartar",
                      corpo(autor, "justificativa",
                            pedido.getParameter("pmoJustificativa")),
                      "Item descartado. O material segue no cofre como prova.");
      case "revogar_liberacao":
        return enviar("POST", "/liberacoes/" + id(pedido) + "/revogar",
                      corpo(autor), "Liberacao revogada.");
      case "regra_estado":
        return alternarRegra(autor, id(pedido),
                             "true".equals(pedido.getParameter("pmoAtiva")));
      case "gravar_politica":
        return gravarPolitica(autor, pedido.getParameter("pmoPolitica"));
      case "restaurar_modelos":
        return restaurarModelos(autor);
      case "indice_estado":
        return enviar("POST", "/indices/" + tipo(pedido) + "/" + id(pedido)
                              + "/estado",
                      corpo(autor, "ativo",
                            "true".equals(pedido.getParameter("pmoAtiva"))
                                ? "@true" : "@false"),
                      "Indice atualizado.");
      case "indice_remover":
        return enviar("DELETE", "/indices/" + tipo(pedido) + "/" + id(pedido),
                      corpo(autor, "confirmar", "@true"),
                      "Indice removido.");
      case "indexar_edm":
        return indexarEdm(autor, pedido);
      case "indexar_idm":
        return enviar("POST", "/indices/idm/"
                              + Html.codificar(valor(pedido.getParameter("pmoNome"))),
                      corpo(autor, "documento",
                            pedido.getParameter("pmoDocumento"),
                            "texto", pedido.getParameter("pmoTexto")),
                      "Documento registrado no indice IDM.");
      case "dicionario_gravar":
        return gravarDicionario(autor, pedido);
      case "dicionario_remover":
        return enviar("DELETE", "/dicionarios/" + id(pedido), corpo(autor),
                      "Dicionario removido.");
      case "varrer":
        return enviar("POST", "/descoberta/varreduras",
                      corpo(autor, "origem", pedido.getParameter("pmoOrigem"),
                            "alvo", pedido.getParameter("pmoAlvo"),
                            "modo", pedido.getParameter("pmoModo")),
                      "Varredura iniciada.");
      case "cancelar_varredura":
        return enviar("POST", "/descoberta/varreduras/" + id(pedido) + "/cancelar",
                      corpo(autor), "Cancelamento pedido.");
      case "reenviar_aviso":
        return enviar("POST", "/notificacoes/" + id(pedido) + "/reenviar",
                      corpo(autor), "Aviso recolocado na fila.");
      default:
        throw new IllegalArgumentException("acao desconhecida: " + acao);
    }
  }

  /**
   * Liga/desliga UMA regra sem tocar nas demais.
   *
   * <p>A API grava a politica inteira de uma vez, entao a alternancia le a
   * politica corrente, muda o campo {@code ativa} da regra escolhida e regrava.
   * Fazer isso na tela (mandando o administrador editar o JSON so' para
   * desligar uma linha) seria convite a erro num arquivo em que um caractere
   * fora do lugar desliga a protecao inteira.
   */
  @SuppressWarnings("unchecked")
  private String alternarRegra(String autor, String identificador, boolean ativa)
      throws IOException {
    Map<String, Object> politica = Json.objeto(bruto("/politica"));
    List<Object> regras = Json.lista(politica, "regras");
    boolean achou = false;
    for (Object r : regras) {
      if (r instanceof Map
          && identificador.equals(Json.texto(r, "identificador"))) {
        ((Map<String, Object>) r).put("ativa", ativa);
        achou = true;
      }
    }
    if (!achou) {
      throw new IllegalArgumentException("regra inexistente: " + identificador);
    }
    Map<String, Object> corpo = new LinkedHashMap<>();
    corpo.put("autor", autor);
    corpo.put("regras", regras);
    cliente.repassar("PUT", "/politica", Json.bonito(corpo));
    return "Regra " + identificador + (ativa ? " ligada." : " desligada.");
  }

  @SuppressWarnings("unchecked")
  private String gravarPolitica(String autor, String texto) throws IOException {
    Map<String, Object> lido = Json.objeto(texto == null ? "" : texto);
    List<Object> regras = Json.lista(lido, "regras");
    if (regras.isEmpty()) {
      // Antes de mandar ao servico: a mensagem daqui e' mais util que um 400.
      throw new IllegalArgumentException(
          "O texto nao tem nenhuma regra. Confira se o JSON esta' completo e "
          + "se a chave 'regras' existe. Para desligar uma regra, use o botao "
          + "'desligar' da tabela em vez de apagar o bloco.");
    }
    Map<String, Object> corpo = new LinkedHashMap<>();
    corpo.put("autor", autor);
    corpo.put("regras", regras);
    String resposta = cliente.repassar("PUT", "/politica", Json.bonito(corpo));
    return "Politica gravada com "
           + Json.texto(Json.objeto(resposta), "regras") + " regra(s).";
  }

  private String restaurarModelos(String autor) throws IOException {
    Map<String, Object> modelos = Json.objeto(bruto("/politica/modelos"));
    Map<String, Object> corpo = new LinkedHashMap<>();
    corpo.put("autor", autor);
    corpo.put("regras", Json.lista(modelos, "regras"));
    String resposta = cliente.repassar("PUT", "/politica", Json.bonito(corpo));
    return "Modelos de conformidade restaurados: "
           + Json.texto(Json.objeto(resposta), "regras") + " regra(s).";
  }

  /**
   * Converte o CSV colado em linhas para a indexacao EDM.
   *
   * <p>O separador e' detectado na linha de cabecalho: ponto-e-virgula, que e'
   * o que o Excel em portugues gera, ou virgula. Errar isso produziria um
   * indice de UMA coluna com a linha inteira dentro -- que nao casa com nada e
   * so' seria descoberto quando o DLP deixasse passar o cadastro.
   */
  private String indexarEdm(String autor, ActionRequest pedido) throws IOException {
    String nome = valor(pedido.getParameter("pmoNome"));
    String csv = valor(pedido.getParameter("pmoCsv"));
    String[] linhas = csv.replace("\r", "").split("\n");
    if (linhas.length < 2) {
      throw new IllegalArgumentException(
          "O CSV precisa de uma linha de cabecalho e ao menos uma de dados.");
    }
    char separador = linhas[0].indexOf(';') >= 0 ? ';' : ',';
    List<String> colunas = partir(linhas[0], separador);
    StringBuilder corpo = new StringBuilder(csv.length() + 256);
    corpo.append("{\"autor\":").append(Json.escrever(autor))
         .append(",\"minimo\":")
         .append(numero(pedido.getParameter("pmoMinimo"), "2"))
         .append(",\"colunas\":[");
    for (int i = 0; i < colunas.size(); i++) {
      corpo.append(i > 0 ? "," : "").append(Json.escrever(colunas.get(i)));
    }
    corpo.append("],\"linhas\":[");
    int total = 0;
    for (int l = 1; l < linhas.length; l++) {
      if (linhas[l].trim().isEmpty()) {
        continue;
      }
      List<String> celulas = partir(linhas[l], separador);
      corpo.append(total > 0 ? ",[" : "[");
      for (int i = 0; i < celulas.size(); i++) {
        corpo.append(i > 0 ? "," : "").append(Json.escrever(celulas.get(i)));
      }
      corpo.append(']');
      total++;
    }
    corpo.append("]}");
    if (total == 0) {
      throw new IllegalArgumentException("Nenhuma linha de dados no CSV.");
    }
    String resposta = cliente.repassar(
        "POST", "/indices/edm/" + Html.codificar(nome), corpo.toString());
    Map<String, Object> lido = Json.objeto(resposta);
    return "Indice EDM '" + nome + "' criado com "
           + Json.texto(lido, "registros") + " registro(s) e "
           + Json.texto(lido, "celulas") + " celula(s). Nenhum valor do "
           + "cadastro foi guardado.";
  }

  private String gravarDicionario(String autor, ActionRequest pedido)
      throws IOException {
    String nome = valor(pedido.getParameter("pmoNome"));
    String bruto = valor(pedido.getParameter("pmoTermos"));
    StringBuilder corpo = new StringBuilder();
    corpo.append("{\"autor\":").append(Json.escrever(autor))
         .append(",\"severidade\":")
         .append(Json.escrever(valor(pedido.getParameter("pmoSeveridade"))))
         .append(",\"termos\":[");
    int total = 0;
    for (String linha : bruto.replace("\r", "").split("\n")) {
      String t = linha.trim();
      if (t.isEmpty()) {
        continue;
      }
      corpo.append(total++ > 0 ? "," : "").append(Json.escrever(t));
    }
    corpo.append("]}");
    if (total == 0) {
      throw new IllegalArgumentException("Nenhum termo informado.");
    }
    cliente.repassar("PUT", "/dicionarios/" + Html.codificar(nome),
                     corpo.toString());
    return "Dicionario '" + nome + "' gravado com " + total
           + " termo(s) e ja' em uso na varredura.";
  }

  private static List<String> partir(String linha, char separador) {
    List<String> saida = new ArrayList<>();
    for (String parte : linha.split(String.valueOf(separador), -1)) {
      saida.add(parte.trim());
    }
    return saida;
  }

  // ===========================================================================
  // Download
  // ===========================================================================
  @Override
  public void serveResource(ResourceRequest pedido, ResourceResponse resposta)
      throws PortletException, IOException {
    if (!ehAdministrador()) {
      // Terceira porta, e a mais perigosa: aqui sai o ORIGINAL retido.
      LOG.warn("Console de DLP: download recusado, usuario fora de {}",
               grupoAdministradores);
      resposta.setProperty(ResourceResponse.HTTP_STATUS_CODE, "403");
      return;
    }
    String tipo = valor(pedido.getParameter("pmoRecurso"));
    try {
      if ("quarentena".equals(tipo)) {
        String identificador = valor(pedido.getParameter("pmoId"));
        byte[] conteudo = cliente.baixar(
            "/quarentena/" + Html.codificar(identificador) + "/conteudo");
        Map<String, Object> item = Json.objeto(
            bruto("/quarentena/" + Html.codificar(identificador)));
        String nome = Json.texto(item, "nome_arquivo");
        resposta.setContentType("application/octet-stream");
        resposta.setProperty("Content-Disposition",
            "attachment; filename=\"" + (nome.isEmpty() ? identificador : nome)
            .replace("\"", "") + "\"");
        LOG.info("Console de DLP: {} baixou o conteudo retido {}",
                 usuarioAtual(), identificador);
        try (OutputStream saida = resposta.getPortletOutputStream()) {
          saida.write(conteudo);
        }
        return;
      }
      if ("csv".equals(tipo)) {
        Map<String, String> filtros = new LinkedHashMap<>();
        for (String nome : new String[] {"busca", "severidade", "canal",
                                         "usuario", "estado"}) {
          String v = valor(pedido.getParameter(nome));
          if (!v.isEmpty()) {
            filtros.put(nome, v);
          }
        }
        byte[] conteudo = cliente.baixar("/relatorios/incidentes.csv?"
                                         + Html.consulta(filtros));
        resposta.setContentType("text/csv; charset=utf-8");
        resposta.setProperty("Content-Disposition",
                             "attachment; filename=\"incidentes-dlp.csv\"");
        try (OutputStream saida = resposta.getPortletOutputStream()) {
          saida.write(conteudo);
        }
        return;
      }
      resposta.setProperty(ResourceResponse.HTTP_STATUS_CODE, "404");
    } catch (IOException e) {
      LOG.error("Console de DLP: falha ao servir o recurso {}", tipo, e);
      resposta.setProperty(ResourceResponse.HTTP_STATUS_CODE, "502");
      resposta.setContentType("text/plain; charset=utf-8");
      resposta.getWriter().write("Falha ao obter o conteudo: " + mensagemDe(e));
    }
  }

  // ===========================================================================
  // Apoio
  // ===========================================================================
  private Object obter(String caminho) {
    try {
      return Json.objeto(cliente.repassar("GET", caminho, null));
    } catch (IOException e) {
      LOG.warn("Console de DLP: {} indisponivel: {}", caminho, e.getMessage());
      return null;
    }
  }

  private String bruto(String caminho) throws IOException {
    return cliente.repassar("GET", caminho, null);
  }

  private String enviar(String metodo, String caminho, String corpo,
                        String sucesso) throws IOException {
    cliente.repassar(metodo, caminho, corpo);
    return sucesso;
  }

  /**
   * Monta o corpo JSON com o autor e pares chave/valor.
   *
   * <p>Valor iniciado por {@code @} entra como literal (para {@code true} e
   * {@code false}); o resto e' escapado como texto. Sem essa distincao,
   * {@code "ativo": "false"} chegaria como a STRING "false", que em Python e'
   * verdadeira -- e desativar um indice o deixaria ativo.
   */
  private static String corpo(String autor, String... pares) {
    StringBuilder b = new StringBuilder("{\"autor\":");
    b.append(Json.escrever(autor));
    for (int i = 0; i + 1 < pares.length; i += 2) {
      String valor = pares[i + 1];
      if (valor == null || valor.isEmpty()) {
        continue;
      }
      b.append(',').append(Json.escrever(pares[i])).append(':');
      if (valor.charAt(0) == '@') {
        b.append(valor.substring(1));
      } else {
        b.append(Json.escrever(valor));
      }
    }
    return b.append('}').toString();
  }

  private static String id(ActionRequest pedido) {
    String v = valor(pedido.getParameter("pmoId"));
    if (v.isEmpty()) {
      throw new IllegalArgumentException("identificador ausente");
    }
    return Html.codificar(v);
  }

  private static String tipo(ActionRequest pedido) {
    String v = valor(pedido.getParameter("pmoTipo"));
    if (!"edm".equals(v) && !"idm".equals(v)) {
      throw new IllegalArgumentException("tipo de indice invalido: " + v);
    }
    return v;
  }

  /** Extrai a mensagem util do erro devolvido pela API. */
  private static String mensagemDe(Exception e) {
    String texto = e.getMessage() == null ? e.toString() : e.getMessage();
    int json = texto.indexOf('{');
    if (json >= 0) {
      String erro = Json.texto(Json.objeto(texto.substring(json)), "erro");
      if (!erro.isEmpty()) {
        return erro;
      }
    }
    return texto;
  }

  private static String aba(String valor) {
    if (valor == null) {
      return "painel";
    }
    for (Pagina.Aba a : Pagina.ABAS) {
      if (a.codigo.equals(valor)) {
        return a.codigo;
      }
    }
    return "painel";
  }

  private static String valor(String v) {
    return v == null ? "" : v.trim();
  }

  /** "busca" -> "Busca". Liga o nome do filtro ao nome do campo do formulario. */
  private static String maiuscula(String v) {
    return v.isEmpty() ? v
        : Character.toUpperCase(v.charAt(0)) + v.substring(1);
  }

  private static String numero(String v, String padrao) {
    if (v == null || v.trim().isEmpty()) {
      return padrao;
    }
    String t = v.trim();
    for (int i = 0; i < t.length(); i++) {
      if (!Character.isDigit(t.charAt(i))) {
        return padrao;
      }
    }
    return t;
  }

  private static String consumir(PortletSession sessao, String chave) {
    Object v = sessao.getAttribute(chave, PortletSession.PORTLET_SCOPE);
    if (v == null) {
      return "";
    }
    sessao.removeAttribute(chave, PortletSession.PORTLET_SCOPE);
    return String.valueOf(v);
  }

  // ------------------------------------------------------------- identidade
  private boolean ehAdministrador() {
    ConversationState estado = ConversationState.getCurrent();
    if (estado == null) {
      // Ausencia de identidade e' o caso do anonimo E o de uma via de
      // invocacao que ninguem previu. Os dois fecham a porta.
      return false;
    }
    Identity identidade = estado.getIdentity();
    return identidade != null && identidade.isMemberOf(grupoAdministradores);
  }

  private String usuarioAtual() {
    ConversationState estado = ConversationState.getCurrent();
    if (estado == null || estado.getIdentity() == null) {
      return "";
    }
    String id = estado.getIdentity().getUserId();
    return id == null ? "" : id;
  }

  private String texto(String nome, String padrao) {
    String v = getInitParameter(nome);
    return v == null || v.trim().isEmpty() ? padrao : v.trim();
  }

  private int inteiro(String nome, int padrao) {
    String v = getInitParameter(nome);
    if (v == null || v.trim().isEmpty()) {
      return padrao;
    }
    try {
      int lido = Integer.parseInt(v.trim());
      if (lido <= 0) {
        LOG.warn("Parametro {}={} nao e' positivo; usando {}", nome, v, padrao);
        return padrao;
      }
      return lido;
    } catch (NumberFormatException e) {
      LOG.warn("Parametro {}={} nao e' inteiro; usando {}", nome, v, padrao);
      return padrao;
    }
  }
}
