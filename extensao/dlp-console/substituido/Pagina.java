package br.pmo.dlpconsole;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A TELA do console de DLP, montada no servidor.
 *
 * <p><b>O QUE ISTO ENCERRA.</b> {@code dlp/PENDENCIAS.md} listava, como item
 * mais visivel do que ficou faltando: "A TELA do console. A API esta' completa
 * e provada; NAO EXISTE portlet visual. Hoje a gestao so' e' possivel por
 * chamada REST." Era o unico caminho de administracao do DLP -- um
 * administrador de prefeitura nao opera politica de vazamento por
 * {@code curl}.
 *
 * <p><b>POR QUE RENDERIZADO NO SERVIDOR, e nao um aplicativo de navegador.</b>
 * Tres razoes, e nenhuma e' preferencia:
 * <ol>
 *   <li>O TOKEN da API nunca chega ao navegador. Se a tela chamasse o servico
 *       direto, o token estaria no JavaScript -- ou seja, com qualquer usuario
 *       autenticado do portal.</li>
 *   <li>O servico de DLP nao tem porta publicada; so' existe na rede interna
 *       do compose. E' o desenho certo para algo que le todo arquivo que
 *       passa, e obriga o portal a intermediar.</li>
 *   <li>Um pacote Vue exigiria cadeia de compilacao de JavaScript e moveria o
 *       ESCAPE de HTML para dentro de um artefato que so' se testa com o
 *       portal de pe'. Aqui a montagem inteira e' funcao pura e se prova no
 *       host.</li>
 * </ol>
 *
 * <p>A tela funciona com JavaScript desligado: tudo e' formulario e link.
 */
public final class Pagina {

  private Pagina() {
  }

  /** Uma aba do console: o codigo que vai na URL e o rotulo que aparece. */
  public static final class Aba {
    public final String codigo;
    public final String rotulo;

    Aba(String codigo, String rotulo) {
      this.codigo = codigo;
      this.rotulo = rotulo;
    }
  }

  /**
   * As abas, em ordem de leitura.
   *
   * <p>Lista IMUTAVEL de objetos imutaveis, e nao um {@code String[][]}
   * publico: vetor publico e' mutavel por quem o obtem, e uma constante que o
   * chamador consegue alterar deixa de ser constante. O portlet le esta lista
   * para montar as RenderURL e para validar a aba pedida na URL.
   */
  public static final List<Aba> ABAS = java.util.Collections.unmodifiableList(
      java.util.Arrays.asList(
          new Aba("painel", "Painel"),
          new Aba("incidentes", "Incidentes"),
          new Aba("revisao", "Revis\u00e3o"),
          new Aba("quarentena", "Quarentena"),
          new Aba("politica", "Pol\u00edtica"),
          new Aba("indices", "\u00cdndices"),
          new Aba("dicionarios", "Dicion\u00e1rios"),
          new Aba("descoberta", "Descoberta"),
          new Aba("notificacoes", "Avisos"),
          new Aba("agentes", "Agentes"),
          new Aba("auditoria", "Auditoria")));

  private static final List<String> ESTADOS = Arrays.asList(
      "NOVO", "EM_ANALISE", "ESCALADO", "CONFIRMADO", "FALSO_POSITIVO",
      "RESOLVIDO");

  private static final List<String> SEVERIDADES = Arrays.asList(
      "", "BAIXA", "MEDIA", "ALTA", "CRITICA");

  private static final List<String> CANAIS = Arrays.asList(
      "", "DOWNLOAD", "LINK_PUBLICO", "COMPARTILHAMENTO_EXTERNO", "EMAIL",
      "EMAIL_INTERNO", "CHAT", "EDITOR", "NUVEM", "API", "WEBDAV", "IMPRESSAO",
      "USB", "CLIPBOARD", "ENDPOINT", "ICAP", "DESCOBERTA");

  // ===========================================================================
  public static String pagina(Tela tela, Map<String, Object> dados) {
    StringBuilder b = new StringBuilder(16384);
    b.append(estilo());
    b.append("<div class=\"pmo-dlp\">");
    b.append(topo(tela));
    b.append(avisos(tela));
    switch (tela.aba) {
      case "incidentes": b.append(incidentes(tela, dados)); break;
      case "revisao": b.append(revisao(tela, dados)); break;
      case "quarentena": b.append(quarentena(tela, dados)); break;
      case "politica": b.append(politica(tela, dados)); break;
      case "indices": b.append(indices(tela, dados)); break;
      case "dicionarios": b.append(dicionarios(tela, dados)); break;
      case "descoberta": b.append(descoberta(tela, dados)); break;
      case "notificacoes": b.append(notificacoes(tela, dados)); break;
      case "agentes": b.append(agentes(tela, dados)); break;
      case "auditoria": b.append(auditoria(tela, dados)); break;
      default: b.append(painel(tela, dados));
    }
    b.append("</div>");
    return b.toString();
  }

  /** Tela mostrada a quem nao pertence ao grupo exigido. */
  public static String acessoNegado(String grupo) {
    return estilo() + "<div class=\"pmo-dlp\"><h1>Console de DLP</h1>"
           + "<div class=\"pmo-erro\">Esta tela mostra incidentes de vazamento "
           + "de dados pessoais, com o nome de quem tentou a transferencia. O "
           + "acesso exige participacao no grupo "
           + Html.t(grupo) + ".</div></div>";
  }

  // --------------------------------------------------------------- estrutura
  private static String topo(Tela tela) {
    StringBuilder b = new StringBuilder();
    b.append("<h1 class=\"pmo-titulo\">Prote\u00e7\u00e3o de dados (DLP)</h1>");
    b.append("<nav class=\"pmo-abas\">");
    for (Aba aba : ABAS) {
      boolean atual = aba.codigo.equals(tela.aba);
      b.append("<a class=\"pmo-aba").append(atual ? " pmo-aba-atual" : "")
       .append("\" href=\"").append(Html.t(tela.url(aba.codigo))).append("\">")
       .append(Html.t(aba.rotulo)).append("</a>");
    }
    b.append("</nav>");
    return b.toString();
  }

  private static String avisos(Tela tela) {
    StringBuilder b = new StringBuilder();
    if (!tela.erro.isEmpty()) {
      b.append("<div class=\"pmo-erro\">").append(Html.t(tela.erro)).append("</div>");
    }
    if (!tela.mensagem.isEmpty()) {
      b.append("<div class=\"pmo-ok\">").append(Html.t(tela.mensagem)).append("</div>");
    }
    return b.toString();
  }

  /** Abre um formulario POST para a ActionURL do portlet, ja' com a acao. */
  private static String formulario(Tela tela, String acao) {
    return "<form method=\"post\" action=\"" + Html.t(tela.urlAcao) + "\">"
           + Html.campoOculto("pmoAcao", acao)
           + Html.campoOculto("pmoAba", tela.aba);
  }

  /** Idem, deixando a tag ABERTA para receber atributos (class, id). */
  private static String formularioAberto(Tela tela, String acao) {
    return "<form method=\"post\" action=\"" + Html.t(tela.urlAcao) + "\"";
  }

  // ----------------------------------------------------------------- painel
  private static String painel(Tela tela, Map<String, Object> dados) {
    Object saude = dados.get("saude");
    Object painel = dados.get("painel");
    StringBuilder b = new StringBuilder();

    if (saude == null) {
      return Html.vazio("O servico de DLP nao respondeu. Confira o container "
                        + "exo-dlp e a propriedade exo.dlp.url.");
    }

    Map<String, Object> quarentena = Json.mapa(saude, "quarentena");
    Map<String, Object> avisos = Json.mapa(saude, "notificacoes");
    b.append("<div class=\"pmo-cartoes\">");
    b.append(Html.cartao("Incidentes", Html.numero(Json.inteiro(saude, "incidentes", 0)),
                         "no total", "#1a5fb4"));
    b.append(Html.cartao("Esperando revisao",
                         Html.numero(Json.inteiro(saude, "revisao_pendente", 0)),
                         "decisao humana pendente", "#c25e00"));
    b.append(Html.cartao("Em quarentena",
                         Html.numero(Json.inteiro(quarentena, "RETIDO", 0)),
                         "retidos no cofre", "#b3261e"));
    b.append(Html.cartao("Avisos na fila",
                         Html.numero(Json.inteiro(avisos, "PENDENTE", 0)),
                         Json.inteiro(avisos, "FALHA", 0) + " em falha",
                         Json.inteiro(avisos, "FALHA", 0) > 0 ? "#b3261e" : "#1b5e20"));
    b.append(Html.cartao("Regras ativas", Html.numero(Json.inteiro(saude, "regras", 0)),
                         "na politica vigente", "#5e2750"));
    b.append("</div>");

    // Estado dos componentes: o que esta' ligado e o que nao esta'.
    b.append("<h2>Componentes</h2><div class=\"pmo-estado\">");
    Map<String, Object> correio = Json.mapa(saude, "correio");
    b.append(linhaEstado("Correio de avisos",
                         Json.logico(correio, "ativo", false),
                         Json.logico(correio, "ativo", false)
                             ? "relay " + Json.texto(correio, "host")
                             : "DLP_NOTIFICA_SMTP_HOST vazio: as acoes "
                               + "NOTIFICAR_* ficam em FALHA na fila"));
    Map<String, Object> siem = Json.mapa(saude, "siem");
    b.append(linhaEstado("Envio a SIEM", Json.logico(siem, "ativo", false),
                         Json.logico(siem, "ativo", false)
                             ? Json.texto(siem, "formato").toUpperCase()
                               + " para " + Json.texto(siem, "host")
                             : "DLP_SIEM_HOST vazio"));
    Map<String, Object> descoberta = Json.mapa(saude, "descoberta");
    List<Object> origens = Json.lista(descoberta, "origens");
    b.append(linhaEstado("Descoberta em repouso", !origens.isEmpty(),
                         origens.isEmpty()
                             ? "nenhuma origem configurada"
                             : origens.size() + " origem(ns): "
                               + Html.juntarCampo(origens, "nome", ", ")));
    List<Object> edm = Json.lista(saude, "indices_edm");
    List<Object> idm = Json.lista(saude, "indices_idm");
    b.append(linhaEstado("Indices EDM/IDM", !edm.isEmpty() || !idm.isEmpty(),
                         edm.size() + " EDM, " + idm.size() + " IDM"));
    b.append("</div>");

    if (painel != null) {
      b.append(agregado("Por severidade", Json.lista(painel, "por_severidade")));
      b.append(agregado("Por canal", Json.lista(painel, "por_canal")));
      b.append(agregado("Por regra", Json.lista(painel, "por_regra")));
      b.append(agregado("Por usuario", Json.lista(painel, "por_usuario")));
    }
    return b.toString();
  }

  private static String linhaEstado(String nome, boolean ligado, String detalhe) {
    return "<div class=\"pmo-linha-estado\">"
           + Html.etiqueta(ligado ? "ativo" : "inativo",
                           ligado ? "#1b5e20" : "#6b6b6b")
           + "<b>" + Html.t(nome) + "</b> <span>" + Html.t(detalhe) + "</span></div>";
  }

  private static String agregado(String titulo, List<Object> itens) {
    if (itens.isEmpty()) {
      return "";
    }
    long maior = 1;
    for (Object o : itens) {
      maior = Math.max(maior, Json.inteiro(o, "total", 0));
    }
    StringBuilder b = new StringBuilder("<h2>").append(Html.t(titulo))
        .append("</h2><table class=\"pmo-tab pmo-barras\">");
    for (Object o : itens) {
      long total = Json.inteiro(o, "total", 0);
      b.append("<tr><td class=\"pmo-chave\">")
       .append(Html.curto(Json.texto(o, "chave"), 42))
       .append("</td><td class=\"pmo-barra\"><span style=\"width:")
       .append(Math.max(2, total * 100 / maior)).append("%\"></span></td>")
       .append("<td class=\"pmo-n\">").append(Html.numero(total))
       .append("</td></tr>");
    }
    return b.append("</table>").toString();
  }

  // ------------------------------------------------------------- incidentes
  private static String incidentes(Tela tela, Map<String, Object> dados) {
    Object detalhe = dados.get("incidente");
    if (detalhe != null) {
      return detalheIncidente(tela, detalhe);
    }
    Object lista = dados.get("incidentes");
    StringBuilder b = new StringBuilder();
    // Formulario POST para a ActionURL. Um GET com parametros crus nao chega
    // ao portlet: o contentor so' entrega o que foi codificado numa PortletURL.
    b.append(formularioAberto(tela, "filtrar")).append(" class=\"pmo-filtros\">")
     .append(Html.campoOculto("pmoAcao", "filtrar"))
     .append(Html.campoOculto("pmoAba", tela.aba))
     .append("<input type=\"text\" name=\"pmoBusca\" placeholder=\"arquivo, recurso ou destino\" value=\"")
     .append(Html.t(tela.filtro("busca"))).append("\">")
     .append("<select name=\"pmoSeveridade\">")
     .append(Html.opcoes(SEVERIDADES, tela.filtro("severidade")))
     .append("</select>")
     .append("<select name=\"pmoCanal\">")
     .append(Html.opcoes(CANAIS, tela.filtro("canal")))
     .append("</select>")
     .append("<input type=\"text\" name=\"pmoUsuario\" placeholder=\"usuario\" value=\"")
     .append(Html.t(tela.filtro("usuario"))).append("\">")
     .append("<button type=\"submit\">Filtrar</button>")
     .append("</form>")
     .append(formulario(tela, "limpar_filtros"))
     .append("<button type=\"submit\" class=\"pmo-claro\">Limpar</button></form>")
     .append("<a class=\"pmo-botao-claro\" href=\"").append(Html.t(tela.urlCsv))
     .append("\">Exportar CSV</a>");

    if (lista == null) {
      return b.append(Html.vazio("O servico de DLP nao respondeu.")).toString();
    }
    List<Object> itens = Json.lista(lista, "itens");
    if (itens.isEmpty()) {
      return b.append(Html.vazio("Nenhum incidente com estes filtros.")).toString();
    }
    b.append("<p class=\"pmo-total\">").append(Html.numero(Json.inteiro(lista, "total", 0)))
     .append(" incidente(s)</p>");
    b.append("<table class=\"pmo-tab\"><thead><tr>")
     .append("<th>Quando</th><th>Severidade</th><th>Canal</th><th>Usuario</th>")
     .append("<th>Arquivo</th><th>Regra</th><th>Acoes</th><th>Estado</th><th></th>")
     .append("</tr></thead><tbody>");
    for (Object i : itens) {
      String id = Json.texto(i, "identificador");
      b.append("<tr><td>").append(Html.momento(Json.texto(i, "momento")))
       .append("</td><td>")
       .append(Html.etiqueta(Json.texto(i, "severidade"),
                             Html.corSeveridade(Json.texto(i, "severidade"))))
       .append("</td><td>").append(Html.t(Json.texto(i, "canal")))
       .append("</td><td>").append(Html.curto(Json.texto(i, "usuario"), 22))
       .append("</td><td>").append(Html.curto(Json.texto(i, "nome_arquivo"), 30))
       .append("</td><td>").append(Html.curto(Json.texto(i, "regra_nome"), 28))
       .append("</td><td class=\"pmo-acoes-col\">")
       .append(acoesResumo(i))
       .append("</td><td>")
       .append(Html.etiqueta(Json.texto(i, "estado"),
                             Html.corEstado(Json.texto(i, "estado"))))
       .append("</td><td><a href=\"")
       .append(Html.t(tela.comId(tela.urlDetalhe, id))).append("\">abrir</a>")
       .append("</td></tr>");
    }
    return b.append("</tbody></table>").toString();
  }

  /**
   * Mostra o que a regra PEDIU e o que ACONTECEU, lado a lado.
   *
   * <p>E' a coluna que faltava: enquanto o incidente guardava so' `acoes`, um
   * QUARENTENAR que apenas bloqueava ficava indistinguivel de um que reteve o
   * arquivo. Acao pedida e nao cumprida aparece riscada.
   */
  private static String acoesResumo(Object incidente) {
    List<Object> pedidas = Json.lista(incidente, "acoes");
    List<Object> feitas = Json.lista(incidente, "acoes_executadas");
    StringBuilder b = new StringBuilder();
    for (Object p : pedidas) {
      String nome = String.valueOf(p);
      boolean fez = false;
      for (Object f : feitas) {
        if (nome.equals(String.valueOf(f))) {
          fez = true;
          break;
        }
      }
      b.append("<span class=\"").append(fez ? "pmo-fez" : "pmo-nao-fez")
       .append("\">").append(Html.t(nome)).append("</span> ");
    }
    return b.toString();
  }

  private static String detalheIncidente(Tela tela, Object i) {
    StringBuilder b = new StringBuilder();
    b.append("<p><a href=\"").append(Html.t(tela.url("incidentes")))
     .append("\">&larr; voltar a lista</a></p>");
    b.append("<h2>Incidente ").append(Html.t(Json.texto(i, "identificador")))
     .append("</h2>");

    b.append("<dl class=\"pmo-detalhe\">");
    b.append(Html.parBruto("Severidade",
        Html.etiqueta(Json.texto(i, "severidade"),
                      Html.corSeveridade(Json.texto(i, "severidade")))));
    b.append(Html.parBruto("Estado",
        Html.etiqueta(Json.texto(i, "estado"),
                      Html.corEstado(Json.texto(i, "estado")))));
    b.append(Html.par("Momento", Json.texto(i, "momento")));
    b.append(Html.par("Classificacao", Json.texto(i, "classificacao")));
    b.append(Html.par("Canal", Json.texto(i, "canal")));
    b.append(Html.par("Origem", Json.texto(i, "origem")));
    b.append(Html.par("Usuario", Json.texto(i, "usuario")));
    b.append(Html.par("Endereco de rede", Json.texto(i, "ip")));
    b.append(Html.par("Destino", Json.texto(i, "destino")));
    b.append(Html.par("Arquivo", Json.texto(i, "nome_arquivo")));
    b.append(Html.par("Tipo real", Json.texto(i, "tipo_arquivo")
                                   + " (" + Json.texto(i, "mime") + ")"));
    b.append(Html.par("Tamanho", Html.numero(Json.inteiro(i, "tamanho", 0)) + " bytes"));
    b.append(Html.par("Recurso", Json.texto(i, "recurso")));
    b.append(Html.par("Regra", Json.texto(i, "regra_nome")
                               + " [" + Json.texto(i, "regra") + "]"));
    b.append(Html.par("Motivo", Json.texto(i, "motivo")));
    b.append(Html.parBruto("Acoes", acoesResumo(i)));
    b.append(Html.par("Responsavel", Json.texto(i, "responsavel")));
    if (Json.logico(i, "disfarcado", false)) {
      b.append(Html.parBruto("Atencao", Html.etiqueta(
          "extensao nao corresponde ao tipo real", "#b3261e")));
    }
    if (!Json.logico(i, "extracao_completa", true)) {
      b.append(Html.parBruto("Leitura", Html.etiqueta("PARCIAL", "#c25e00")
          + " " + Html.t(Json.texto(i, "motivo_parcial"))));
    }
    if (!Json.texto(i, "quarentena").isEmpty()) {
      b.append(Html.parBruto("Quarentena",
          "<a href=\"" + Html.t(tela.url("quarentena")) + "\">"
          + Html.t(Json.texto(i, "quarentena")) + "</a>"));
    }
    if (!Json.texto(i, "liberacao").isEmpty()) {
      b.append(Html.par("Liberado por", Json.texto(i, "liberacao")));
    }
    b.append("</dl>");

    List<Object> naoAplicaveis = Json.lista(i, "acoes_nao_aplicaveis");
    if (!naoAplicaveis.isEmpty()) {
      b.append("<h3>Acoes que a regra pediu e nao foi possivel cumprir</h3>")
       .append("<table class=\"pmo-tab\"><thead><tr><th>Acao</th><th>Motivo</th>")
       .append("</tr></thead><tbody>");
      for (Object n : naoAplicaveis) {
        b.append("<tr><td>").append(Html.t(Json.texto(n, "acao")))
         .append("</td><td>").append(Html.t(Json.texto(n, "motivo")))
         .append("</td></tr>");
      }
      b.append("</tbody></table>");
    }

    List<Object> evidencia = Json.lista(i, "evidencia");
    b.append("<h3>Evidencia <small>(sempre mascarada: o console nao guarda o "
             + "valor)</small></h3>");
    if (evidencia.isEmpty()) {
      b.append(Html.vazio("Sem achado isolado."));
    } else {
      b.append("<table class=\"pmo-tab\"><thead><tr><th>Tipo</th><th>Sev.</th>")
       .append("<th>Qtd</th><th>Trecho</th></tr></thead><tbody>");
      for (Object e : evidencia) {
        List<Object> amostras = Json.lista(e, "amostras");
        String trecho = amostras.isEmpty() ? ""
            : Json.texto(amostras.get(0), "trecho");
        b.append("<tr><td>").append(Html.t(Json.texto(e, "rotulo")))
         .append("</td><td>")
         .append(Html.etiqueta(Json.texto(e, "severidade"),
                               Html.corSeveridade(Json.texto(e, "severidade"))))
         .append("</td><td>").append(Html.t(Json.texto(e, "quantidade")))
         .append("</td><td class=\"pmo-trecho\">").append(Html.curto(trecho, 160))
         .append("</td></tr>");
      }
      b.append("</tbody></table>");
    }

    b.append("<h3>Trilha</h3><table class=\"pmo-tab\"><tbody>");
    for (Object t : Json.lista(i, "trilha")) {
      b.append("<tr><td>").append(Html.momento(Json.texto(t, "momento")))
       .append("</td><td>").append(Html.t(Json.texto(t, "autor")))
       .append("</td><td>").append(Html.t(Json.texto(t, "acao")))
       .append("</td><td>").append(Html.curto(Json.texto(t, "detalhe"), 120))
       .append("</td></tr>");
    }
    b.append("</tbody></table>");

    List<Object> anotacoes = Json.lista(i, "anotacoes");
    if (!anotacoes.isEmpty()) {
      b.append("<h3>Anotacoes</h3><ul class=\"pmo-anotacoes\">");
      for (Object a : anotacoes) {
        b.append("<li><b>").append(Html.t(Json.texto(a, "autor"))).append("</b> ")
         .append(Html.momento(Json.texto(a, "momento"))).append("<br>")
         .append(Html.t(Json.texto(a, "texto"))).append("</li>");
      }
      b.append("</ul>");
    }

    String id = Json.texto(i, "identificador");
    b.append("<div class=\"pmo-formularios\">");
    b.append(formulario(tela, "estado")).append(Html.campoOculto("pmoId", id))
     .append("<label>Mudar estado <select name=\"pmoEstado\">")
     .append(Html.opcoes(ESTADOS, Json.texto(i, "estado")))
     .append("</select></label>")
     .append("<input type=\"text\" name=\"pmoDetalhe\" placeholder=\"detalhe\">")
     .append("<button type=\"submit\">Aplicar</button></form>");

    b.append(formulario(tela, "atribuir")).append(Html.campoOculto("pmoId", id))
     .append("<label>Atribuir a <input type=\"text\" name=\"pmoResponsavel\" ")
     .append("value=\"").append(Html.t(Json.texto(i, "responsavel")))
     .append("\" placeholder=\"login do analista\"></label>")
     .append("<button type=\"submit\">Atribuir</button></form>");

    b.append(formulario(tela, "anotar")).append(Html.campoOculto("pmoId", id))
     .append("<label>Anotacao <textarea name=\"pmoTexto\" rows=\"2\" ")
     .append("placeholder=\"o que foi apurado\"></textarea></label>")
     .append("<button type=\"submit\">Anotar</button></form>");
    b.append("</div>");
    return b.toString();
  }

  // ---------------------------------------------------------------- revisao
  private static String revisao(Tela tela, Map<String, Object> dados) {
    Object fila = dados.get("revisao");
    StringBuilder b = new StringBuilder();
    b.append("<p class=\"pmo-explica\">Transferencias que a politica mandou "
             + "encaminhar para decisao humana. Aprovar cria uma liberacao "
             + "nominal, com prazo e contagem de usos, e a mesma transferencia "
             + "passa a ser permitida para aquele usuario.</p>");
    if (fila == null) {
      return b.append(Html.vazio("O servico de DLP nao respondeu.")).toString();
    }
    List<Object> itens = Json.lista(fila, "itens");
    if (itens.isEmpty()) {
      return b.append(Html.vazio("Nada esperando revisao.")).toString();
    }
    for (Object i : itens) {
      String id = Json.texto(i, "identificador");
      b.append("<div class=\"pmo-cartao-item\">");
      b.append("<div class=\"pmo-cabecalho-item\">")
       .append(Html.etiqueta(Json.texto(i, "severidade"),
                             Html.corSeveridade(Json.texto(i, "severidade"))))
       .append("<b>").append(Html.curto(Json.texto(i, "nome_arquivo"), 60))
       .append("</b> <span>").append(Html.t(Json.texto(i, "usuario")))
       .append(" &middot; ").append(Html.t(Json.texto(i, "canal")))
       .append(" &middot; ").append(Html.momento(Json.texto(i, "momento")))
       .append("</span></div>");
      b.append("<p class=\"pmo-motivo\">").append(Html.curto(Json.texto(i, "motivo"), 200))
       .append("</p>");
      List<Object> evidencia = Json.lista(i, "evidencia");
      if (!evidencia.isEmpty()) {
        b.append("<p class=\"pmo-trecho\">");
        for (Object e : evidencia) {
          b.append(Html.etiqueta(Json.texto(e, "rotulo") + " x"
                                 + Json.texto(e, "quantidade"),
                                 Html.corSeveridade(Json.texto(e, "severidade"))));
        }
        List<Object> amostras = Json.lista(evidencia.get(0), "amostras");
        if (!amostras.isEmpty()) {
          b.append(' ').append(Html.curto(Json.texto(amostras.get(0), "trecho"), 160));
        }
        b.append("</p>");
      }
      b.append("<div class=\"pmo-formularios\">");
      b.append(formulario(tela, "aprovar")).append(Html.campoOculto("pmoId", id))
       .append("<input type=\"text\" name=\"pmoJustificativa\" required ")
       .append("placeholder=\"por que esta transferencia pode ocorrer\">")
       .append("<label>horas <input type=\"number\" name=\"pmoHoras\" value=\"24\" ")
       .append("min=\"1\" max=\"720\"></label>")
       .append("<label>usos <input type=\"number\" name=\"pmoUsos\" value=\"1\" ")
       .append("min=\"1\" max=\"999\"></label>")
       .append("<button type=\"submit\">Aprovar</button></form>");
      b.append(formulario(tela, "reprovar")).append(Html.campoOculto("pmoId", id))
       .append("<input type=\"text\" name=\"pmoJustificativa\" required ")
       .append("placeholder=\"por que nao\">")
       .append("<button type=\"submit\" class=\"pmo-negativo\">Reprovar</button></form>");
      b.append("</div><p><a href=\"")
       .append(Html.t(tela.comId(tela.urlDetalhe, id)))
       .append("\">ver o incidente inteiro</a></p>");
      b.append("</div>");
    }
    return b.toString();
  }

  // ------------------------------------------------------------- quarentena
  private static String quarentena(Tela tela, Map<String, Object> dados) {
    Object q = dados.get("quarentena");
    StringBuilder b = new StringBuilder();
    b.append("<p class=\"pmo-explica\">Conteudo RETIDO, guardado cifrado no "
             + "cofre. Liberar devolve a transferencia ao usuario; descartar "
             + "impede a volta e PRESERVA o material como prova do incidente.</p>");
    if (q == null) {
      return b.append(Html.vazio("O servico de DLP nao respondeu.")).toString();
    }
    Map<String, Object> resumo = Json.mapa(q, "resumo");
    b.append("<div class=\"pmo-cartoes\">")
     .append(Html.cartao("Retidos", Html.numero(Json.inteiro(resumo, "RETIDO", 0)),
                         "esperando decisao", "#b3261e"))
     .append(Html.cartao("Liberados", Html.numero(Json.inteiro(resumo, "LIBERADO", 0)),
                         "devolvidos ao usuario", "#1b5e20"))
     .append(Html.cartao("Descartados", Html.numero(Json.inteiro(resumo, "DESCARTADO", 0)),
                         "material preservado", "#4a6572"))
     .append("</div>");

    List<Object> itens = Json.lista(q, "itens");
    if (itens.isEmpty()) {
      return b.append(Html.vazio("Nada em quarentena.")).toString();
    }
    for (Object i : itens) {
      String id = Json.texto(i, "identificador");
      String estado = Json.texto(i, "estado");
      b.append("<div class=\"pmo-cartao-item\">");
      b.append("<div class=\"pmo-cabecalho-item\">")
       .append(Html.etiqueta(estado, "RETIDO".equals(estado) ? "#b3261e" : "#4a6572"))
       .append("<b>").append(Html.curto(Json.texto(i, "nome_arquivo"), 60))
       .append("</b> <span>").append(Html.t(Json.texto(i, "usuario")))
       .append(" &middot; ").append(Html.t(Json.texto(i, "canal")))
       .append(" &middot; ").append(Html.momento(Json.texto(i, "momento")))
       .append(" &middot; ").append(Html.numero(Json.inteiro(i, "tamanho", 0)))
       .append(" bytes</span></div>");
      b.append("<dl class=\"pmo-detalhe pmo-compacto\">")
       .append(Html.par("Regra", Json.texto(i, "regra_nome")))
       .append(Html.par("Recurso", Json.texto(i, "recurso")))
       .append(Html.par("sha256 do original", Json.texto(i, "sha256")))
       .append(Html.par("Motivo", Json.texto(i, "motivo")));
      if (!Json.texto(i, "decidido_por").isEmpty()) {
        b.append(Html.par("Decidido por", Json.texto(i, "decidido_por") + " em "
                                          + Json.texto(i, "decidido_em")))
         .append(Html.par("Justificativa", Json.texto(i, "justificativa")));
      }
      b.append("</dl>");
      b.append("<p><a class=\"pmo-botao-claro\" href=\"")
       .append(Html.t(tela.comId(tela.urlQuarentenaConteudo, id)))
       .append("\">Baixar o original retido</a></p>");
      if ("RETIDO".equals(estado)) {
        b.append("<div class=\"pmo-formularios\">");
        b.append(formulario(tela, "liberar_quarentena"))
         .append(Html.campoOculto("pmoId", id))
         .append("<input type=\"text\" name=\"pmoJustificativa\" required ")
         .append("placeholder=\"por que pode voltar a circular\">")
         .append("<label>horas <input type=\"number\" name=\"pmoHoras\" value=\"24\" ")
         .append("min=\"1\" max=\"720\"></label>")
         .append("<button type=\"submit\">Liberar</button></form>");
        b.append(formulario(tela, "descartar_quarentena"))
         .append(Html.campoOculto("pmoId", id))
         .append("<input type=\"text\" name=\"pmoJustificativa\" required ")
         .append("placeholder=\"por que nao volta\">")
         .append("<button type=\"submit\" class=\"pmo-negativo\">Descartar</button>")
         .append("</form></div>");
      }
      b.append("</div>");
    }

    Object liberacoes = dados.get("liberacoes");
    if (liberacoes != null) {
      List<Object> lista = Json.lista(liberacoes, "itens");
      b.append("<h2>Liberacoes concedidas</h2>");
      if (lista.isEmpty()) {
        b.append(Html.vazio("Nenhuma liberacao concedida."));
      } else {
        b.append("<table class=\"pmo-tab\"><thead><tr><th>Quando</th><th>Autor</th>")
         .append("<th>Usuario</th><th>Recurso</th><th>Expira</th><th>Usos</th>")
         .append("<th>Estado</th><th></th></tr></thead><tbody>");
        for (Object l : lista) {
          String estado = Json.texto(l, "estado");
          b.append("<tr><td>").append(Html.momento(Json.texto(l, "momento")))
           .append("</td><td>").append(Html.t(Json.texto(l, "autor")))
           .append("</td><td>").append(Html.t(Json.texto(l, "usuario")))
           .append("</td><td>").append(Html.curto(Json.texto(l, "recurso"), 34))
           .append("</td><td>").append(Html.momento(Json.texto(l, "expira_em")))
           .append("</td><td>").append(Html.t(Json.texto(l, "usos"))).append('/')
           .append(Html.t(Json.texto(l, "teto_usos")))
           .append("</td><td>").append(Html.etiqueta(estado,
               "ATIVA".equals(estado) ? "#1b5e20" : "#6b6b6b"))
           .append("</td><td>");
          if ("ATIVA".equals(estado)) {
            b.append(formulario(tela, "revogar_liberacao"))
             .append(Html.campoOculto("pmoId", Json.texto(l, "identificador")))
             .append("<button type=\"submit\" class=\"pmo-negativo\">Revogar</button>")
             .append("</form>");
          }
          b.append("</td></tr>");
        }
        b.append("</tbody></table>");
      }
    }
    return b.toString();
  }

  // --------------------------------------------------------------- politica
  private static String politica(Tela tela, Map<String, Object> dados) {
    Object p = dados.get("politica");
    StringBuilder b = new StringBuilder();
    if (p == null) {
      return Html.vazio("O servico de DLP nao respondeu.");
    }
    List<Object> regras = Json.lista(p, "regras");
    b.append("<p class=\"pmo-explica\">Uma regra responde a: QUEM, levando O "
             + "QUE, por QUAL canal, para ONDE, QUANDO. Acao riscada na coluna "
             + "'acoes' e' acao que o motor nao consegue cumprir para aquele "
             + "tipo de conteudo.</p>");
    b.append("<table class=\"pmo-tab\"><thead><tr><th>Prio</th><th>Regra</th>")
     .append("<th>Condicao</th><th>Acoes</th><th>Sev.</th><th>Conformidade</th>")
     .append("<th>Ativa</th></tr></thead><tbody>");
    for (Object r : regras) {
      Object condicao = Json.mapa(r, "condicao");
      boolean ativa = Json.logico(r, "ativa", true);
      b.append("<tr><td>").append(Html.t(Json.texto(r, "prioridade")))
       .append("</td><td><b>").append(Html.t(Json.texto(r, "nome")))
       .append("</b><br><small>").append(Html.t(Json.texto(r, "identificador")))
       .append("</small></td><td class=\"pmo-cond\">").append(condicaoTexto(condicao))
       .append("</td><td>").append(Html.t(Json.juntar(Json.lista(r, "acoes"), ", ")))
       .append("</td><td>")
       .append(Html.etiqueta(Json.texto(r, "severidade"),
                             Html.corSeveridade(Json.texto(r, "severidade"))))
       .append("</td><td>")
       .append(Html.t(Json.juntar(Json.lista(r, "conformidade"), ", ")))
       .append("</td><td>").append(formulario(tela, "regra_estado"))
       .append(Html.campoOculto("pmoId", Json.texto(r, "identificador")))
       .append(Html.campoOculto("pmoAtiva", ativa ? "false" : "true"))
       .append("<button type=\"submit\" class=\"")
       .append(ativa ? "pmo-negativo" : "").append("\">")
       .append(ativa ? "desligar" : "ligar").append("</button></form></td></tr>");
    }
    b.append("</tbody></table>");

    b.append("<h2>Editar a politica</h2>")
     .append("<p class=\"pmo-explica\">O texto abaixo e' a politica vigente "
             + "inteira. Gravar substitui todas as regras. Politica sem regra "
             + "nenhuma e' recusada de proposito: para desligar uma regra, use "
             + "o botao 'desligar' da tabela -- assim fica registrado quem "
             + "desligou o que, e quando.</p>");
    b.append(formulario(tela, "gravar_politica"))
     .append("<textarea name=\"pmoPolitica\" rows=\"22\" class=\"pmo-codigo\">")
     .append(Html.t(String.valueOf(dados.get("politica_bruta"))))
     .append("</textarea>")
     .append("<button type=\"submit\">Gravar politica</button>")
     .append("</form>");

    b.append(formulario(tela, "restaurar_modelos"))
     .append("<p class=\"pmo-explica\">Restaurar substitui a politica pelos "
             + "modelos de conformidade prontos (PCI-DSS, LGPD, PHI, evasao, "
             + "EDM, IDM). Serve quando a edicao chegou a um ponto de nao "
             + "funcionar mais.</p>")
     .append("<button type=\"submit\" class=\"pmo-negativo\">Restaurar modelos "
             + "prontos</button></form>");
    return b.toString();
  }

  private static String condicaoTexto(Object c) {
    StringBuilder b = new StringBuilder();
    acrescentar(b, "rotulos", Json.juntar(Json.lista(c, "rotulos"), ", "));
    acrescentar(b, "categorias", Json.juntar(Json.lista(c, "categorias"), ", "));
    acrescentar(b, "sev.min", Json.texto(c, "severidade_minima"));
    acrescentar(b, "canais", Json.juntar(Json.lista(c, "canais"), ", "));
    acrescentar(b, "usuarios", Json.juntar(Json.lista(c, "usuarios"), ", "));
    acrescentar(b, "grupos", Json.juntar(Json.lista(c, "grupos"), ", "));
    acrescentar(b, "ips", Json.juntar(Json.lista(c, "ips"), ", "));
    acrescentar(b, "destinos", Json.juntar(Json.lista(c, "destinos"), ", "));
    acrescentar(b, "tipos", Json.juntar(Json.lista(c, "tipos_arquivo"), ", "));
    acrescentar(b, "EDM", Json.juntar(Json.lista(c, "indice_edm"), ", "));
    acrescentar(b, "IDM", Json.juntar(Json.lista(c, "indice_idm"), ", "));
    acrescentar(b, "horario", Json.texto(c, "horario_inicio").isEmpty() ? ""
        : Json.texto(c, "horario_inicio") + "-" + Json.texto(c, "horario_fim"));
    return b.length() == 0 ? "&mdash;" : b.toString();
  }

  private static void acrescentar(StringBuilder b, String rotulo, String valor) {
    if (valor == null || valor.isEmpty() || "BAIXA".equals(valor)) {
      return;
    }
    if (b.length() > 0) {
      b.append("<br>");
    }
    b.append("<i>").append(Html.t(rotulo)).append(":</i> ").append(Html.t(valor));
  }

  // ---------------------------------------------------------------- indices
  private static String indices(Tela tela, Map<String, Object> dados) {
    Object idx = dados.get("indices");
    StringBuilder b = new StringBuilder();
    b.append("<p class=\"pmo-explica\">EDM reconhece registros do cadastro "
             + "oficial (nome + matricula da folha, por exemplo). IDM reconhece "
             + "o documento inteiro ou um trecho colado. Nenhum dos dois guarda "
             + "o dado: so' HMAC com o sal desta instalacao.</p>");
    if (idx == null) {
      return b.append(Html.vazio("O servico de DLP nao respondeu.")).toString();
    }
    b.append("<h2>EDM</h2>");
    List<Object> edm = Json.lista(idx, "edm");
    if (edm.isEmpty()) {
      b.append(Html.vazio("Nenhum indice EDM."));
    } else {
      b.append("<table class=\"pmo-tab\"><thead><tr><th>Nome</th><th>Colunas</th>")
       .append("<th>Registros</th><th>Min.</th><th>Atualizado</th><th>Ativo</th>")
       .append("<th></th></tr></thead><tbody>");
      for (Object e : edm) {
        String nome = Json.texto(e, "nome");
        boolean ativo = Json.logico(e, "ativo", true);
        b.append("<tr><td>").append(Html.t(nome)).append("</td><td>")
         .append(Html.t(Json.juntar(Json.lista(e, "colunas"), ", ")))
         .append("</td><td>").append(Html.t(Json.texto(e, "total_registros")))
         .append("</td><td>").append(Html.t(Json.texto(e, "minimo")))
         .append("</td><td>").append(Html.momento(Json.texto(e, "atualizado_em")))
         .append("</td><td>").append(formulario(tela, "indice_estado"))
         .append(Html.campoOculto("pmoTipo", "edm"))
         .append(Html.campoOculto("pmoId", nome))
         .append(Html.campoOculto("pmoAtiva", ativo ? "false" : "true"))
         .append("<button type=\"submit\">").append(ativo ? "desligar" : "ligar")
         .append("</button></form></td><td>").append(formulario(tela, "indice_remover"))
         .append(Html.campoOculto("pmoTipo", "edm"))
         .append(Html.campoOculto("pmoId", nome))
         .append("<button type=\"submit\" class=\"pmo-negativo\">remover</button>")
         .append("</form></td></tr>");
      }
      b.append("</tbody></table>");
    }

    b.append("<h2>IDM</h2>");
    List<Object> idm = Json.lista(idx, "idm");
    if (idm.isEmpty()) {
      b.append(Html.vazio("Nenhum indice IDM."));
    } else {
      b.append("<table class=\"pmo-tab\"><thead><tr><th>Nome</th><th>Documentos</th>")
       .append("<th>Ativo</th><th></th></tr></thead><tbody>");
      for (Object e : idm) {
        String nome = Json.texto(e, "nome");
        boolean ativo = Json.logico(e, "ativo", true);
        StringBuilder docs = new StringBuilder();
        for (Object d : Json.lista(e, "documentos")) {
          docs.append(docs.length() == 0 ? "" : ", ")
              .append(Json.texto(d, "documento")).append(" (")
              .append(Json.texto(d, "janelas")).append(")");
        }
        b.append("<tr><td>").append(Html.t(nome)).append("</td><td>")
         .append(Html.curto(docs.toString(), 90)).append("</td><td>")
         .append(formulario(tela, "indice_estado"))
         .append(Html.campoOculto("pmoTipo", "idm"))
         .append(Html.campoOculto("pmoId", nome))
         .append(Html.campoOculto("pmoAtiva", ativo ? "false" : "true"))
         .append("<button type=\"submit\">").append(ativo ? "desligar" : "ligar")
         .append("</button></form></td><td>").append(formulario(tela, "indice_remover"))
         .append(Html.campoOculto("pmoTipo", "idm"))
         .append(Html.campoOculto("pmoId", nome))
         .append("<button type=\"submit\" class=\"pmo-negativo\">remover</button>")
         .append("</form></td></tr>");
      }
      b.append("</tbody></table>");
    }

    b.append("<h2>Cadastrar indice EDM</h2>")
     .append("<p class=\"pmo-explica\">Cole o cadastro em CSV, com cabecalho. "
             + "O conteudo NAO e' guardado: cada celula vira um HMAC e o texto "
             + "original e' descartado ao fim da indexacao.</p>")
     .append(formulario(tela, "indexar_edm"))
     .append("<label>Nome <input type=\"text\" name=\"pmoNome\" required ")
     .append("placeholder=\"folha-pagamento\"></label>")
     .append("<label>Minimo de colunas para casar ")
     .append("<input type=\"number\" name=\"pmoMinimo\" value=\"2\" min=\"1\" max=\"20\">")
     .append("</label>")
     .append("<textarea name=\"pmoCsv\" rows=\"8\" class=\"pmo-codigo\" required ")
     .append("placeholder=\"nome;matricula&#10;Maria Aparecida Souza;2024-00871\">")
     .append("</textarea>")
     .append("<button type=\"submit\">Indexar</button></form>");

    b.append("<h2>Registrar documento no IDM</h2>")
     .append(formulario(tela, "indexar_idm"))
     .append("<label>Indice <input type=\"text\" name=\"pmoNome\" required ")
     .append("placeholder=\"sigilosos\"></label>")
     .append("<label>Documento <input type=\"text\" name=\"pmoDocumento\" required ")
     .append("placeholder=\"edital-2026\"></label>")
     .append("<textarea name=\"pmoTexto\" rows=\"8\" required ")
     .append("placeholder=\"cole aqui o texto do documento a proteger\"></textarea>")
     .append("<button type=\"submit\">Registrar</button></form>");
    return b.toString();
  }

  // ------------------------------------------------------------ dicionarios
  private static String dicionarios(Tela tela, Map<String, Object> dados) {
    Object d = dados.get("dicionarios");
    StringBuilder b = new StringBuilder();
    b.append("<p class=\"pmo-explica\">Termos proprios da casa que o catalogo "
             + "de padroes nao conhece: nome de operacao, codigo de projeto, "
             + "designacao interna. O casamento ignora acento e caixa.</p>");
    if (d == null) {
      return b.append(Html.vazio("O servico de DLP nao respondeu.")).toString();
    }
    Map<String, Object> cadastrados = Json.mapa(d, "cadastrados");
    if (cadastrados.isEmpty()) {
      b.append(Html.vazio("Nenhum dicionario cadastrado."));
    } else {
      b.append("<table class=\"pmo-tab\"><thead><tr><th>Nome</th><th>Termos</th>")
       .append("<th>Severidade</th><th>Atualizado</th><th></th></tr></thead><tbody>");
      for (Map.Entry<String, Object> e : cadastrados.entrySet()) {
        b.append("<tr><td>").append(Html.t(e.getKey())).append("</td><td>")
         .append(Html.curto(Json.juntar(Json.lista(e.getValue(), "termos"), ", "), 90))
         .append("</td><td>").append(Html.t(Json.texto(e.getValue(), "severidade")))
         .append("</td><td>")
         .append(Html.momento(Json.texto(e.getValue(), "atualizado_em")))
         .append("</td><td>").append(formulario(tela, "dicionario_remover"))
         .append(Html.campoOculto("pmoId", e.getKey()))
         .append("<button type=\"submit\" class=\"pmo-negativo\">remover</button>")
         .append("</form></td></tr>");
      }
      b.append("</tbody></table>");
    }
    b.append("<h2>Cadastrar ou substituir</h2>")
     .append(formulario(tela, "dicionario_gravar"))
     .append("<label>Nome (vira o rotulo do achado) ")
     .append("<input type=\"text\" name=\"pmoNome\" required ")
     .append("placeholder=\"PROJETO_SIGILOSO\"></label>")
     .append("<label>Severidade <select name=\"pmoSeveridade\">")
     .append(Html.opcoes(Arrays.asList("BAIXA", "MEDIA", "ALTA", "CRITICA"), "MEDIA"))
     .append("</select></label>")
     .append("<textarea name=\"pmoTermos\" rows=\"8\" required ")
     .append("placeholder=\"um termo por linha\"></textarea>")
     .append("<button type=\"submit\">Gravar</button></form>");
    return b.toString();
  }

  // ------------------------------------------------------------- descoberta
  private static String descoberta(Tela tela, Map<String, Object> dados) {
    StringBuilder b = new StringBuilder();
    b.append("<p class=\"pmo-explica\">Varredura de dados em REPOUSO. Responde "
             + "'onde estao os dados pessoais que eu tenho', que e' a pergunta "
             + "anterior a 'isto pode sair'. A varredura NAO move, NAO renomeia "
             + "e NAO apaga arquivo: ela classifica e abre incidente.</p>");
    Object origens = dados.get("origens");
    List<Object> lista = origens == null ? new java.util.ArrayList<>()
        : Json.lista(origens, "itens");
    if (lista.isEmpty()) {
      b.append(Html.vazio("Nenhuma origem configurada. Defina "
                          + "DLP_DESCOBERTA_URL (acervo do portal por WebDAV) "
                          + "ou DLP_DESCOBERTA_CAMINHOS (compartilhamento "
                          + "montado no container)."));
    } else {
      b.append("<table class=\"pmo-tab\"><thead><tr><th>Origem</th><th>Tipo</th>")
       .append("<th>Onde</th></tr></thead><tbody>");
      for (Object o : lista) {
        b.append("<tr><td>").append(Html.t(Json.texto(o, "nome")))
         .append("</td><td>").append(Html.t(Json.texto(o, "tipo")))
         .append("</td><td>").append(Html.curto(Json.texto(o, "descricao"), 80))
         .append("</td></tr>");
      }
      b.append("</tbody></table>");
      b.append("<h2>Iniciar varredura</h2>")
       .append(formulario(tela, "varrer"))
       .append("<label>Origem <select name=\"pmoOrigem\">");
      for (Object o : lista) {
        b.append("<option value=\"").append(Html.t(Json.texto(o, "nome")))
         .append("\">").append(Html.t(Json.texto(o, "nome"))).append("</option>");
      }
      b.append("</select></label>")
       .append("<label>Alvo (opcional) <input type=\"text\" name=\"pmoAlvo\" ")
       .append("placeholder=\"subpasta\"></label>")
       .append("<label>Modo <select name=\"pmoModo\">")
       .append("<option value=\"INCREMENTAL\">INCREMENTAL (so' o que mudou)</option>")
       .append("<option value=\"COMPLETA\">COMPLETA</option></select></label>")
       .append("<button type=\"submit\">Varrer</button></form>");
    }

    Object varreduras = dados.get("varreduras");
    b.append("<h2>Varreduras</h2>");
    if (varreduras == null) {
      return b.append(Html.vazio("O servico de DLP nao respondeu.")).toString();
    }
    List<Object> itens = Json.lista(varreduras, "itens");
    if (itens.isEmpty()) {
      return b.append(Html.vazio("Nenhuma varredura executada.")).toString();
    }
    b.append("<table class=\"pmo-tab\"><thead><tr><th>Quando</th><th>Origem</th>")
     .append("<th>Modo</th><th>Estado</th><th>Lidos</th><th>Com achado</th>")
     .append("<th>Pulados</th><th>Erros</th><th>Detalhe</th><th></th>")
     .append("</tr></thead><tbody>");
    for (Object v : itens) {
      String estado = Json.texto(v, "estado");
      b.append("<tr><td>").append(Html.momento(Json.texto(v, "momento")))
       .append("</td><td>").append(Html.t(Json.texto(v, "origem")))
       .append("</td><td>").append(Html.t(Json.texto(v, "modo")))
       .append("</td><td>").append(Html.etiqueta(estado,
           "CONCLUIDA".equals(estado) ? "#1b5e20"
               : "EM_ANDAMENTO".equals(estado) ? "#1a5fb4" : "#b3261e"))
       .append("</td><td>").append(Html.t(Json.texto(v, "inspecionados")))
       .append("</td><td>").append(Html.t(Json.texto(v, "com_achado")))
       .append("</td><td>").append(Html.t(Json.texto(v, "ignorados")))
       .append("</td><td>").append(Html.t(Json.texto(v, "erros")))
       .append("</td><td>").append(Html.curto(Json.texto(v, "detalhe"), 70))
       .append("</td><td>");
      if ("EM_ANDAMENTO".equals(estado)) {
        b.append(formulario(tela, "cancelar_varredura"))
         .append(Html.campoOculto("pmoId", Json.texto(v, "identificador")))
         .append("<button type=\"submit\" class=\"pmo-negativo\">cancelar</button>")
         .append("</form>");
      }
      b.append("</td></tr>");
    }
    return b.append("</tbody></table>").toString();
  }

  // ----------------------------------------------------------- notificacoes
  private static String notificacoes(Tela tela, Map<String, Object> dados) {
    Object n = dados.get("notificacoes");
    StringBuilder b = new StringBuilder();
    b.append("<p class=\"pmo-explica\">Avisos gerados pelas acoes "
             + "NOTIFICAR_USUARIO, NOTIFICAR_ADMIN e ORIENTAR. A fila e' "
             + "persistente e reenvia sozinha; o que esgotou as tentativas fica "
             + "em FALHA aqui, com o erro -- nunca some em silencio.</p>");
    if (n == null) {
      return b.append(Html.vazio("O servico de DLP nao respondeu.")).toString();
    }
    Map<String, Object> resumo = Json.mapa(n, "resumo");
    b.append("<div class=\"pmo-cartoes\">")
     .append(Html.cartao("Pendentes", Html.numero(Json.inteiro(resumo, "PENDENTE", 0)),
                         "na fila", "#1a5fb4"))
     .append(Html.cartao("Enviados", Html.numero(Json.inteiro(resumo, "ENVIADA", 0)),
                         "entregues ao relay", "#1b5e20"))
     .append(Html.cartao("Em falha", Html.numero(Json.inteiro(resumo, "FALHA", 0)),
                         "exigem acao", "#b3261e"))
     .append("</div>");
    List<Object> itens = Json.lista(n, "itens");
    if (itens.isEmpty()) {
      return b.append(Html.vazio("Nenhum aviso na fila.")).toString();
    }
    b.append("<table class=\"pmo-tab\"><thead><tr><th>Quando</th><th>Tipo</th>")
     .append("<th>Destino</th><th>Assunto</th><th>Estado</th><th>Tentativas</th>")
     .append("<th>Erro</th><th></th></tr></thead><tbody>");
    for (Object i : itens) {
      String estado = Json.texto(i, "estado");
      b.append("<tr><td>").append(Html.momento(Json.texto(i, "momento")))
       .append("</td><td>").append(Html.t(Json.texto(i, "tipo")))
       .append("</td><td>").append(Html.curto(Json.texto(i, "destinatario"), 30))
       .append("</td><td>").append(Html.curto(Json.texto(i, "assunto"), 46))
       .append("</td><td>").append(Html.etiqueta(estado,
           "ENVIADA".equals(estado) ? "#1b5e20"
               : "FALHA".equals(estado) ? "#b3261e" : "#1a5fb4"))
       .append("</td><td>").append(Html.t(Json.texto(i, "tentativas")))
       .append("</td><td>").append(Html.curto(Json.texto(i, "ultimo_erro"), 60))
       .append("</td><td>");
      if ("FALHA".equals(estado)) {
        b.append(formulario(tela, "reenviar_aviso"))
         .append(Html.campoOculto("pmoId", Json.texto(i, "id")))
         .append("<button type=\"submit\">reenviar</button></form>");
      }
      b.append("</td></tr>");
    }
    return b.append("</tbody></table>").toString();
  }

  // ---------------------------------------------------------------- agentes
  private static String agentes(Tela tela, Map<String, Object> dados) {
    Object a = dados.get("agentes");
    StringBuilder b = new StringBuilder();
    b.append("<p class=\"pmo-explica\">Agentes de estacao registrados no "
             + "servico. O conector esta' pronto (POST /agentes/registrar e "
             + "POST /analisar) e a politica vigente e' entregue no registro. "
             + "Enquanto nenhum agente estiver instalado, esta lista fica "
             + "vazia -- e nao ha' inspecao de USB, area de transferencia nem "
             + "impressora.</p>");
    if (a == null) {
      return b.append(Html.vazio("O servico de DLP nao respondeu.")).toString();
    }
    List<Object> itens = Json.lista(a, "agentes");
    if (itens.isEmpty()) {
      return b.append(Html.vazio("Nenhum agente registrado.")).toString();
    }
    b.append("<table class=\"pmo-tab\"><thead><tr><th>Identificador</th>")
     .append("<th>Nome</th><th>Sistema</th><th>Versao</th><th>Visto</th>")
     .append("<th>Politica</th></tr></thead><tbody>");
    for (Object i : itens) {
      boolean velha = Json.logico(i, "politica_desatualizada", false);
      b.append("<tr><td>").append(Html.t(Json.texto(i, "identificador")))
       .append("</td><td>").append(Html.t(Json.texto(i, "nome")))
       .append("</td><td>").append(Html.t(Json.texto(i, "sistema")))
       .append("</td><td>").append(Html.t(Json.texto(i, "versao")))
       .append("</td><td>").append(Html.momento(Json.texto(i, "visto_em")))
       .append("</td><td>").append(Html.etiqueta(velha ? "desatualizada" : "em dia",
           velha ? "#c25e00" : "#1b5e20"))
       .append("</td></tr>");
    }
    return b.append("</tbody></table>").toString();
  }

  // -------------------------------------------------------------- auditoria
  private static String auditoria(Tela tela, Map<String, Object> dados) {
    Object a = dados.get("auditoria");
    if (a == null) {
      return Html.vazio("O servico de DLP nao respondeu.");
    }
    StringBuilder b = new StringBuilder();
    b.append("<p class=\"pmo-explica\">Toda alteracao de politica, indice, "
             + "dicionario, quarentena e liberacao fica aqui, com autor.</p>");
    b.append("<table class=\"pmo-tab\"><thead><tr><th>Quando</th><th>Autor</th>")
     .append("<th>Acao</th><th>Alvo</th><th>Detalhe</th></tr></thead><tbody>");
    for (Object i : Json.lista(a, "itens")) {
      b.append("<tr><td>").append(Html.momento(Json.texto(i, "momento")))
       .append("</td><td>").append(Html.t(Json.texto(i, "autor")))
       .append("</td><td>").append(Html.t(Json.texto(i, "acao")))
       .append("</td><td>").append(Html.curto(Json.texto(i, "alvo"), 40))
       .append("</td><td>").append(Html.curto(Json.texto(i, "detalhe"), 90))
       .append("</td></tr>");
    }
    return b.append("</tbody></table>").toString();
  }

  // ------------------------------------------------------------------ folha
  static String estilo() {
    return "<style>"
        + ".pmo-dlp{font:14px/1.5 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;"
        + "color:#1f2328;max-width:1180px;padding:1rem}"
        + ".pmo-titulo{font-size:1.5rem;margin:0 0 .75rem}"
        + ".pmo-dlp h2{font-size:1.1rem;margin:1.5rem 0 .5rem;"
        + "border-bottom:1px solid #e1e4e8;padding-bottom:.25rem}"
        + ".pmo-dlp h3{font-size:1rem;margin:1.1rem 0 .4rem}"
        + ".pmo-dlp h3 small{font-weight:400;color:#666}"
        + ".pmo-abas{display:flex;flex-wrap:wrap;gap:.25rem;border-bottom:2px solid #e1e4e8;"
        + "margin-bottom:1rem}"
        + ".pmo-aba{padding:.45rem .8rem;text-decoration:none;color:#444;border-radius:4px 4px 0 0}"
        + ".pmo-aba:hover{background:#f0f2f4}"
        + ".pmo-aba-atual{background:#1a5fb4;color:#fff;font-weight:600}"
        + ".pmo-cartoes{display:flex;flex-wrap:wrap;gap:.75rem;margin:.5rem 0 1rem}"
        + ".pmo-cartao{background:#fff;border:1px solid #e1e4e8;border-radius:6px;"
        + "padding:.7rem 1rem;min-width:9.5rem}"
        + ".pmo-cartao-n{font-size:1.7rem;font-weight:700;line-height:1.1}"
        + ".pmo-cartao-r{font-size:.85rem;color:#444}"
        + ".pmo-cartao-d{font-size:.75rem;color:#777}"
        + ".pmo-tab{width:100%;border-collapse:collapse;font-size:.86rem;margin-bottom:1rem}"
        + ".pmo-tab th{text-align:left;background:#f6f8fa;border-bottom:1px solid #d8dee4;"
        + "padding:.4rem .5rem;font-weight:600}"
        + ".pmo-tab td{border-bottom:1px solid #eef1f4;padding:.4rem .5rem;"
        + "vertical-align:top}"
        + ".pmo-tab tr:hover td{background:#fbfcfd}"
        + ".pmo-eti{display:inline-block;color:#fff;border-radius:3px;padding:.05rem .4rem;"
        + "font-size:.72rem;font-weight:600;margin-right:.3rem}"
        + ".pmo-fez{background:#e6f4ea;color:#1b5e20;border-radius:3px;padding:0 .3rem;"
        + "font-size:.72rem}"
        + ".pmo-nao-fez{background:#fdecea;color:#8a1c13;border-radius:3px;padding:0 .3rem;"
        + "font-size:.72rem;text-decoration:line-through}"
        + ".pmo-vazio{color:#666;font-style:italic;padding:.8rem 0}"
        + ".pmo-explica{color:#444;background:#f6f8fa;border-left:3px solid #b8c0c8;"
        + "padding:.6rem .8rem;margin:.4rem 0 1rem;font-size:.87rem}"
        + ".pmo-erro{background:#fdecea;border-left:3px solid #b3261e;padding:.6rem .8rem;"
        + "margin-bottom:.8rem}"
        + ".pmo-ok{background:#e6f4ea;border-left:3px solid #1b5e20;padding:.6rem .8rem;"
        + "margin-bottom:.8rem}"
        + ".pmo-filtros{display:flex;flex-wrap:wrap;gap:.4rem;align-items:center;"
        + "margin-bottom:.8rem}"
        + ".pmo-filtros input,.pmo-filtros select{padding:.3rem .4rem;border:1px solid #ccd2d8;"
        + "border-radius:4px}"
        + ".pmo-dlp button{background:#1a5fb4;color:#fff;border:0;border-radius:4px;"
        + "padding:.35rem .8rem;cursor:pointer;font-size:.84rem}"
        + ".pmo-dlp button:hover{background:#164e94}"
        + ".pmo-dlp button.pmo-negativo{background:#b3261e}"
        + ".pmo-dlp button.pmo-negativo:hover{background:#8f1e18}"
        + ".pmo-botao-claro{display:inline-block;background:#eef1f4;color:#1f2328;"
        + "border-radius:4px;padding:.35rem .8rem;text-decoration:none;font-size:.84rem}"
        + ".pmo-formularios{display:flex;flex-wrap:wrap;gap:.6rem;align-items:flex-end;"
        + "margin:.6rem 0}"
        + ".pmo-formularios form{display:flex;flex-wrap:wrap;gap:.35rem;align-items:center;"
        + "background:#f6f8fa;border:1px solid #e1e4e8;border-radius:5px;padding:.5rem}"
        + ".pmo-formularios input,.pmo-formularios select,.pmo-formularios textarea{"
        + "padding:.3rem .4rem;border:1px solid #ccd2d8;border-radius:4px}"
        + ".pmo-formularios label{font-size:.8rem;color:#444;display:flex;gap:.3rem;"
        + "align-items:center}"
        + ".pmo-detalhe{display:grid;grid-template-columns:repeat(auto-fill,minmax(20rem,1fr));"
        + "gap:.15rem .8rem;margin:.5rem 0}"
        + ".pmo-par{display:flex;gap:.5rem;border-bottom:1px dotted #e6e9ec;padding:.15rem 0}"
        + ".pmo-par dt{color:#666;min-width:9rem;font-size:.8rem;margin:0}"
        + ".pmo-par dd{margin:0;font-size:.85rem;word-break:break-word}"
        + ".pmo-compacto{grid-template-columns:repeat(auto-fill,minmax(24rem,1fr))}"
        + ".pmo-trecho{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.78rem;"
        + "color:#333;background:#f6f8fa;padding:.2rem .35rem;border-radius:3px}"
        + ".pmo-codigo{width:100%;font-family:ui-monospace,Menlo,Consolas,monospace;"
        + "font-size:.78rem}"
        + ".pmo-cartao-item{border:1px solid #e1e4e8;border-radius:6px;padding:.8rem 1rem;"
        + "margin-bottom:.8rem;background:#fff}"
        + ".pmo-cabecalho-item span{color:#666;font-size:.8rem}"
        + ".pmo-motivo{font-size:.85rem;color:#333;margin:.4rem 0}"
        + ".pmo-linha-estado{display:flex;gap:.5rem;align-items:baseline;padding:.25rem 0;"
        + "border-bottom:1px dotted #e6e9ec;font-size:.86rem}"
        + ".pmo-linha-estado span{color:#555}"
        + ".pmo-barras .pmo-chave{width:38%}"
        + ".pmo-barras .pmo-barra{width:52%}"
        + ".pmo-barras .pmo-barra span{display:block;height:.7rem;background:#1a5fb4;"
        + "border-radius:2px}"
        + ".pmo-barras .pmo-n{text-align:right;font-variant-numeric:tabular-nums}"
        + ".pmo-total{color:#555;font-size:.85rem;margin:.2rem 0 .5rem}"
        + ".pmo-cond{font-size:.78rem;color:#444}"
        + ".pmo-anotacoes{font-size:.85rem;padding-left:1.1rem}"
        + ".pmo-acoes-col{white-space:normal;max-width:14rem}"
        + "</style>";
  }
}
