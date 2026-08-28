package br.pmo.painel;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import br.pmo.dlp.CategoriaConformidade;
import br.pmo.dlp.Classificacao;
import br.pmo.dlp.InstantaneoConformidade;
import br.pmo.dlp.MotivoNaoVarrido;

/**
 * Monta o HTML da tela. JDK puro: nao conhece {@code javax.portlet}.
 *
 * <p><b>POR QUE A MONTAGEM DA TELA FICA FORA DO PORTLET.</b> Porque assim ela e'
 * PROVAVEL. O portlet so' pode ser exercitado dentro de um portal de pe', o que
 * transforma "o percentual sai certo?" e "o texto do administrador escapa do
 * HTML?" em perguntas que so' se respondem com o mouse, depois do deploy. Aqui
 * elas se respondem com {@code javac} e {@code java}, antes de o WAR existir --
 * inclusive a pergunta que mais importa, que e' a do XSS.
 *
 * <p><b>TODO TEXTO QUE APARECE VEM DO ARQUIVO DE IDIOMA.</b> Nao ha' uma frase
 * de interface escrita nesta classe. O que esta' escrito aqui sao CHAVES; o
 * portugues mora em
 * {@code WEB-INF/classes/locale/portlet/painel/Conformidade_pt_BR.properties}.
 * Os unicos textos literais que saem daqui sao numeros, nomes de rotulo do
 * catalogo do motor (CPF, CNPJ...) e as amostras ja' mascaradas.
 *
 * <p><b>TUDO PASSA POR {@link Escape#html(String)}.</b> Sem excecao, e inclusive
 * o que veio do proprio motor: o motivo de varredura incompleta carrega pedaco
 * de nome de arquivo, e nome de arquivo e' texto de terceiro.
 */
public final class PainelHtml {

  // Chaves do arquivo de idioma. Declaradas como constantes para a prova poder
  // conferir a existencia de cada uma sem depender de casar cadeia solta.
  public static final String CH_TITULO = "painel.titulo";
  public static final String CH_ABRANGENCIA = "painel.abrangencia";
  public static final String CH_SITUACAO = "painel.situacao.titulo";
  public static final String CH_COL_CATEGORIA = "painel.col.categoria";
  public static final String CH_COL_QUANTIDADE = "painel.col.quantidade";
  public static final String CH_COL_PERCENTUAL = "painel.col.percentual";
  public static final String CH_TOTAL = "painel.total";
  public static final String CH_SEM_DADOS = "painel.semDados";
  public static final String CH_ALERTA_NVCA = "painel.alerta.naoVarridoComAchado";
  public static final String CH_MOTIVOS = "painel.motivos.titulo";
  public static final String CH_COL_MOTIVO = "painel.col.motivo";
  public static final String CH_COL_ENCAMINHAMENTO = "painel.col.encaminhamento";
  public static final String CH_MOTIVO_CRU = "painel.motivoCru.titulo";
  public static final String CH_TIPOS = "painel.tipos.titulo";
  public static final String CH_COL_TIPO = "painel.col.tipo";
  public static final String CH_COL_ITENS = "painel.col.itens";
  public static final String CH_COL_OCORRENCIAS = "painel.col.ocorrencias";
  public static final String CH_TIPOS_NOTA = "painel.tipos.nota";
  public static final String CH_CLASSIFICACAO = "painel.classificacao.titulo";
  public static final String CH_CLASSIFICACAO_SEM = "painel.classificacao.sem";
  public static final String CH_CSV = "painel.csv.baixar";
  public static final String CH_ANALISE = "painel.analise.titulo";
  public static final String CH_ANALISE_INSTRUCAO = "painel.analise.instrucao";
  public static final String CH_ANALISE_BOTAO = "painel.analise.botao";
  public static final String CH_ANALISE_NAO_ECOA = "painel.analise.naoEcoa";
  public static final String CH_ANALISE_VAZIO = "painel.analise.vazio";
  public static final String CH_ANALISE_FALHOU = "painel.analise.falhou";
  public static final String CH_ANALISE_RESULTADO = "painel.analise.resultado";
  public static final String CH_ANALISE_LIMPO = "painel.analise.limpo";
  public static final String CH_ANALISE_METRICA = "painel.analise.metrica";
  public static final String CH_ANALISE_INCOMPLETA = "painel.analise.incompleta";
  public static final String CH_ANALISE_TRUNCADO = "painel.analise.truncado";
  public static final String CH_ANALISE_CLASSIFICACAO = "painel.analise.classificacao";
  public static final String CH_ANALISE_DECISAO = "painel.analise.decisao";
  public static final String CH_ANALISE_GATILHOS = "painel.analise.gatilhos";
  public static final String CH_COL_ROTULO = "painel.col.rotulo";
  public static final String CH_COL_SEVERIDADE = "painel.col.severidade";
  public static final String CH_COL_AMOSTRAS = "painel.col.amostras";
  public static final String CH_AMOSTRAS_NOTA = "painel.amostras.nota";
  public static final String CH_ACESSO_NEGADO = "painel.acesso.negado";
  public static final String CH_ACESSO_NEGADO_DETALHE = "painel.acesso.negadoDetalhe";

  /** Prefixo das chaves derivadas de enum: {@code painel.categoria.LIMPO} etc. */
  public static final String PRE_CATEGORIA = "painel.categoria.";
  public static final String PRE_MOTIVO = "painel.motivo.";
  public static final String PRE_ENCAMINHAMENTO = "painel.encaminhamento.";
  public static final String PRE_CLASSIFICACAO = "painel.classificacao.";
  public static final String PRE_SEVERIDADE = "painel.severidade.";
  public static final String PRE_ACAO = "painel.acao.";

  private PainelHtml() {
  }

  /**
   * A tela que o administrador SEM permissao ve.
   *
   * <p>Existe para a recusa ser explicita. Devolver a pagina vazia faria a tela
   * parecer quebrada e geraria chamado; devolver o painel sem os dados faria
   * parecer que o acervo esta' limpo. Nenhum dos dois e' a verdade, que e'
   * "voce nao tem permissao para ver isto".
   */
  public static String acessoNegado(Rotulos r, String grupoExigido) {
    StringBuilder sb = new StringBuilder();
    sb.append("<div class=\"pmoPainel pmoPainelNegado\">");
    sb.append("<h3 class=\"pmoTitulo\"><i class=\"uiIconPmo fas fa-lock\" aria-hidden=\"true\"></i> ");
    sb.append(Escape.html(r.de(CH_ACESSO_NEGADO)));
    sb.append("</h3><p class=\"pmoNota\">");
    sb.append(Escape.html(r.formatar(CH_ACESSO_NEGADO_DETALHE, grupoExigido)));
    sb.append("</p></div>");
    return sb.toString();
  }

  /**
   * A tela inteira: cabecalho, relatorio, botao de CSV e ferramenta de analise.
   *
   * @param instantaneo fotografia do relatorio; obrigatorio
   * @param ultima      resumo da ultima analise desta sessao, ou nulo se ainda
   *                    nao houve nenhuma
   * @param urlAcao     URL de acao do portlet para onde o formulario posta
   * @param prefixo     prefixo de identificadores do portlet, para os {@code id}
   *                    nao colidirem com os de outro portlet da mesma pagina
   * @param nomeCampo   nome do parametro do formulario com o texto
   * @param arquivoCsv  nome sugerido para o arquivo baixado
   * @param teto        teto de caracteres da caixa
   * @param r           rotulos
   * @return o HTML, nunca nulo
   */
  public static String pagina(InstantaneoConformidade instantaneo,
                              ResumoAnalise ultima,
                              String urlAcao,
                              String prefixo,
                              String nomeCampo,
                              String arquivoCsv,
                              int teto,
                              Rotulos r) {
    StringBuilder sb = new StringBuilder(8192);
    sb.append("<div class=\"pmoPainel\">");

    sb.append("<h3 class=\"pmoTitulo\">")
      .append("<i class=\"uiIconPmo fas fa-user-shield\" aria-hidden=\"true\"></i> ")
      .append(Escape.html(r.de(CH_TITULO)))
      .append("</h3>");
    sb.append("<p class=\"pmoNota\">").append(Escape.html(r.de(CH_ABRANGENCIA))).append("</p>");

    relatorio(sb, instantaneo, arquivoCsv, prefixo, r);
    analise(sb, ultima, urlAcao, prefixo, nomeCampo, teto, r);

    sb.append("</div>");
    return sb.toString();
  }

  // ===========================================================================
  // Relatorio
  // ===========================================================================

  private static void relatorio(StringBuilder sb,
                                InstantaneoConformidade i,
                                String arquivoCsv,
                                String prefixo,
                                Rotulos r) {
    sb.append("<section class=\"pmoBloco\">");
    sb.append("<div class=\"pmoBlocoCabeca\">");
    sb.append("<h4>").append(Escape.html(r.de(CH_SITUACAO))).append("</h4>");
    botaoCsv(sb, i, arquivoCsv, prefixo, r);
    sb.append("</div>");

    sb.append("<p class=\"pmoTotal\">")
      .append(Escape.html(r.formatar(CH_TOTAL, i.getTotal())))
      .append("</p>");

    if (i.getTotal() == 0) {
      sb.append("<p class=\"pmoVazio\">").append(Escape.html(r.de(CH_SEM_DADOS))).append("</p>");
      sb.append("</section>");
      return;
    }

    // ---- As tres categorias, com quantidade E percentual.
    sb.append("<table class=\"pmoTabela\"><thead><tr>")
      .append("<th>").append(Escape.html(r.de(CH_COL_CATEGORIA))).append("</th>")
      .append("<th class=\"pmoNum\">").append(Escape.html(r.de(CH_COL_QUANTIDADE))).append("</th>")
      .append("<th class=\"pmoNum\">").append(Escape.html(r.de(CH_COL_PERCENTUAL))).append("</th>")
      .append("</tr></thead><tbody>");
    for (CategoriaConformidade c : CategoriaConformidade.values()) {
      sb.append("<tr class=\"pmoCat pmoCat").append(Escape.html(c.name())).append("\">")
        .append("<td>").append(Escape.html(r.de(PRE_CATEGORIA + c.name()))).append("</td>")
        .append("<td class=\"pmoNum\">").append(i.getQuantidade(c)).append("</td>")
        .append("<td class=\"pmoNum\">").append(percentual(i.getPercentual(c))).append("</td>")
        .append("</tr>");
    }
    sb.append("</tbody></table>");

    // ---- O balde mais grave do acervo tem linha propria, nunca diluida.
    if (i.getNaoVarridosComAchado() > 0) {
      sb.append("<p class=\"pmoAlerta\"><i class=\"fas fa-exclamation-triangle\" aria-hidden=\"true\"></i> ")
        .append(Escape.html(r.formatar(CH_ALERTA_NVCA, i.getNaoVarridosComAchado())))
        .append("</p>");
    }

    motivos(sb, i, r);
    tipos(sb, i, r);
    classificacoes(sb, i, r);

    sb.append("</section>");
  }

  /** Motivos de "nao varrido", cada um com o seu encaminhamento ao lado. */
  private static void motivos(StringBuilder sb, InstantaneoConformidade i, Rotulos r) {
    if (i.getQuantidade(CategoriaConformidade.NAO_VARRIDO) == 0) {
      return;
    }
    sb.append("<h5 class=\"pmoSub\">").append(Escape.html(r.de(CH_MOTIVOS))).append("</h5>");
    sb.append("<table class=\"pmoTabela\"><thead><tr>")
      .append("<th>").append(Escape.html(r.de(CH_COL_MOTIVO))).append("</th>")
      .append("<th class=\"pmoNum\">").append(Escape.html(r.de(CH_COL_QUANTIDADE))).append("</th>")
      .append("<th class=\"pmoNum\">").append(Escape.html(r.de(CH_COL_PERCENTUAL))).append("</th>")
      .append("<th>").append(Escape.html(r.de(CH_COL_ENCAMINHAMENTO))).append("</th>")
      .append("</tr></thead><tbody>");
    for (MotivoNaoVarrido m : MotivoNaoVarrido.values()) {
      int n = i.getQuantidade(m);
      if (n == 0) {
        continue;
      }
      sb.append("<tr>")
        .append("<td>").append(Escape.html(r.de(PRE_MOTIVO + m.name()))).append("</td>")
        .append("<td class=\"pmoNum\">").append(n).append("</td>")
        .append("<td class=\"pmoNum\">").append(percentual(i.getPercentual(m))).append("</td>")
        .append("<td class=\"pmoEncaminhamento\">")
        .append(Escape.html(r.de(PRE_ENCAMINHAMENTO + m.name())))
        .append("</td></tr>");
    }
    sb.append("</tbody></table>");

    // Motivos que o classificador nao reconheceu: e' o alarme de deriva, e o
    // texto e' cru -- por isso e' o ponto mais exposto da tela, e escapa igual.
    List<String> crus = i.getAmostrasDeMotivoCru();
    if (!crus.isEmpty()) {
      sb.append("<p class=\"pmoNota\">").append(Escape.html(r.de(CH_MOTIVO_CRU))).append("</p>");
      sb.append("<ul class=\"pmoCrus\">");
      for (String cru : crus) {
        sb.append("<li>").append(Escape.html(cru)).append("</li>");
      }
      sb.append("</ul>");
    }
  }

  /**
   * Itens por tipo E ocorrencias por tipo, lado a lado.
   *
   * <p>Sao numeros diferentes de proposito, e a nota abaixo da tabela diz por
   * que: 300 CPFs em UM contracheque e' um arquivo para tratar; 1 CPF em 300
   * documentos e' um habito espalhado, e a resposta e' treinamento. Publicar so'
   * um dos dois leva a decisao errada com aparencia de dado.
   */
  private static void tipos(StringBuilder sb, InstantaneoConformidade i, Rotulos r) {
    Map<String, Integer> itens = i.getItensPorRotulo();
    if (itens.isEmpty()) {
      return;
    }
    Map<String, Long> ocorrencias = i.getOcorrenciasPorRotulo();
    sb.append("<h5 class=\"pmoSub\">").append(Escape.html(r.de(CH_TIPOS))).append("</h5>");
    sb.append("<table class=\"pmoTabela\"><thead><tr>")
      .append("<th>").append(Escape.html(r.de(CH_COL_TIPO))).append("</th>")
      .append("<th class=\"pmoNum\">").append(Escape.html(r.de(CH_COL_ITENS))).append("</th>")
      .append("<th class=\"pmoNum\">").append(Escape.html(r.de(CH_COL_OCORRENCIAS))).append("</th>")
      .append("</tr></thead><tbody>");
    for (Map.Entry<String, Integer> e : itens.entrySet()) {
      sb.append("<tr>")
        .append("<td>").append(Escape.html(e.getKey())).append("</td>")
        .append("<td class=\"pmoNum\">").append(e.getValue()).append("</td>")
        .append("<td class=\"pmoNum\">")
        .append(ocorrencias.getOrDefault(e.getKey(), 0L))
        .append("</td></tr>");
    }
    sb.append("</tbody></table>");
    sb.append("<p class=\"pmoNota\">").append(Escape.html(r.de(CH_TIPOS_NOTA))).append("</p>");
  }

  /** Classificacao atribuida -- so' do que foi lido por inteiro. */
  private static void classificacoes(StringBuilder sb, InstantaneoConformidade i, Rotulos r) {
    boolean tem = false;
    for (Classificacao c : Classificacao.values()) {
      if (i.getQuantidade(c) > 0) {
        tem = true;
        break;
      }
    }
    int semClassificacao = i.getQuantidade(CategoriaConformidade.NAO_VARRIDO);
    if (!tem && semClassificacao == 0) {
      return;
    }
    sb.append("<h5 class=\"pmoSub\">").append(Escape.html(r.de(CH_CLASSIFICACAO))).append("</h5>");
    sb.append("<table class=\"pmoTabela\"><thead><tr>")
      .append("<th>").append(Escape.html(r.de(CH_COL_CATEGORIA))).append("</th>")
      .append("<th class=\"pmoNum\">").append(Escape.html(r.de(CH_COL_QUANTIDADE))).append("</th>")
      .append("</tr></thead><tbody>");
    for (Classificacao c : Classificacao.values()) {
      if (i.getQuantidade(c) == 0) {
        continue;
      }
      sb.append("<tr class=\"pmoClass pmoClass").append(Escape.html(c.name())).append("\">")
        .append("<td>").append(Escape.html(r.de(PRE_CLASSIFICACAO + c.name()))).append("</td>")
        .append("<td class=\"pmoNum\">").append(i.getQuantidade(c)).append("</td>")
        .append("</tr>");
    }
    if (semClassificacao > 0) {
      // Nao varrido NAO tem classificacao: tem pendencia. Somar isso a PUBLICO
      // publicaria "sao publicos" sobre itens que nunca foram abertos.
      sb.append("<tr class=\"pmoClassSem\">")
        .append("<td>").append(Escape.html(r.de(CH_CLASSIFICACAO_SEM))).append("</td>")
        .append("<td class=\"pmoNum\">").append(semClassificacao).append("</td>")
        .append("</tr>");
    }
    sb.append("</tbody></table>");
  }

  /**
   * O botao de baixar o CSV.
   *
   * <p><b>POR QUE O CSV VIAJA DENTRO DO ATRIBUTO E NAO POR UMA URL.</b> Foi
   * medido nesta plataforma: o caminho de {@code serveResource} da eXo 7.2.1 le
   * dos cabecalhos de transporte APENAS
   * {@code portlet.http-status-code} -- qualquer
   * {@code Content-Disposition} definido pelo portlet e' descartado antes de
   * chegar ao navegador. Um botao apoiado nisso baixaria um arquivo sem nome, ou
   * abriria o CSV dentro da pagina do portal. Como o conteudo ja' e' pequeno e
   * ja' esta' autorizado (quem le a tela pode ler o relatorio), ele viaja no
   * proprio HTML e o navegador monta o arquivo. Menos partes moveis e nenhum
   * ponto de acesso novo para defender.
   *
   * <p>O CSV vem de {@code InstantaneoConformidade.emCsv()}, que ja' neutraliza
   * injecao de formula de planilha. Aqui ele so' e' escapado como atributo.
   */
  private static void botaoCsv(StringBuilder sb,
                               InstantaneoConformidade i,
                               String arquivoCsv,
                               String prefixo,
                               Rotulos r) {
    sb.append("<button type=\"button\" class=\"btn pmoBotaoCsv\"")
      .append(" id=\"").append(Escape.html(prefixo)).append("csv\"")
      .append(" data-pmo-arquivo=\"").append(Escape.html(arquivoCsv)).append("\"")
      .append(" data-pmo-csv=\"").append(Escape.html(i.emCsv())).append("\">")
      .append("<i class=\"fas fa-file-csv\" aria-hidden=\"true\"></i> ")
      .append(Escape.html(r.de(CH_CSV)))
      .append("</button>");
  }

  // ===========================================================================
  // Ferramenta "Analisar texto"
  // ===========================================================================

  private static void analise(StringBuilder sb,
                              ResumoAnalise ultima,
                              String urlAcao,
                              String prefixo,
                              String nomeCampo,
                              int teto,
                              Rotulos r) {
    sb.append("<section class=\"pmoBloco\">");
    sb.append("<h4>").append(Escape.html(r.de(CH_ANALISE))).append("</h4>");
    sb.append("<p class=\"pmoNota\">")
      .append(Escape.html(r.formatar(CH_ANALISE_INSTRUCAO, teto)))
      .append("</p>");

    String idCampo = Escape.html(prefixo) + "texto";
    sb.append("<form method=\"post\" class=\"pmoForm\" action=\"")
      .append(Escape.html(urlAcao)).append("\">");
    sb.append("<label class=\"pmoRotulo\" for=\"").append(idCampo).append("\">")
      .append(Escape.html(r.de(CH_ANALISE))).append("</label>");
    // maxlength e' conveniencia do navegador, NAO defesa: quem posta fora do
    // formulario ignora. A defesa esta' em TextoSubmetido, no servidor.
    sb.append("<textarea id=\"").append(idCampo).append("\" name=\"")
      .append(Escape.html(nomeCampo)).append("\" rows=\"8\" class=\"pmoTexto\" maxlength=\"")
      .append(teto).append("\"></textarea>");
    sb.append("<div class=\"pmoAcoes\">");
    sb.append("<button type=\"submit\" class=\"btn btn-primary pmoBotaoAnalisar\">")
      .append("<i class=\"fas fa-search\" aria-hidden=\"true\"></i> ")
      .append(Escape.html(r.de(CH_ANALISE_BOTAO))).append("</button>");
    sb.append("</div>");
    sb.append("</form>");
    sb.append("<p class=\"pmoNota pmoNaoEcoa\">")
      .append(Escape.html(r.de(CH_ANALISE_NAO_ECOA))).append("</p>");

    resultado(sb, ultima, r);
    sb.append("</section>");
  }

  private static void resultado(StringBuilder sb, ResumoAnalise a, Rotulos r) {
    if (a == null) {
      return;
    }
    sb.append("<div class=\"pmoResultado\">");
    sb.append("<h5 class=\"pmoSub\">").append(Escape.html(r.de(CH_ANALISE_RESULTADO)))
      .append("</h5>");

    if (a.isFalhou()) {
      sb.append("<p class=\"pmoAlerta\">")
        .append(Escape.html(r.de(CH_ANALISE_FALHOU))).append(' ')
        .append(Escape.html(a.getErro()))
        .append("</p></div>");
      return;
    }
    if (a.isVazio()) {
      sb.append("<p class=\"pmoNota\">").append(Escape.html(r.de(CH_ANALISE_VAZIO)))
        .append("</p></div>");
      return;
    }

    sb.append("<p class=\"pmoMetrica\">")
      .append(Escape.html(r.formatar(CH_ANALISE_METRICA,
                                     a.getCaracteresVarridos(),
                                     a.getMilissegundos())))
      .append("</p>");

    if (a.isTruncadoPeloPainel()) {
      sb.append("<p class=\"pmoAlerta\">")
        .append(Escape.html(r.formatar(CH_ANALISE_TRUNCADO,
                                       a.getTamanhoOriginal(),
                                       a.getCaracteresVarridos())))
        .append("</p>");
    }
    if (!a.isCompleta()) {
      sb.append("<p class=\"pmoAlerta\">")
        .append(Escape.html(r.de(CH_ANALISE_INCOMPLETA))).append(' ')
        .append(Escape.html(a.getMotivoIncompleta()))
        .append("</p>");
    }

    sb.append("<p class=\"pmoClassificacaoLinha\">")
      .append(Escape.html(r.de(CH_ANALISE_CLASSIFICACAO))).append(' ')
      .append("<span class=\"pmoSelo pmoClass").append(Escape.html(a.getClassificacao()))
      .append("\">")
      .append(Escape.html(r.de(PRE_CLASSIFICACAO + a.getClassificacao())))
      .append("</span></p>");

    sb.append("<p class=\"pmoDecisaoLinha\">")
      .append(Escape.html(r.de(CH_ANALISE_DECISAO))).append(' ')
      .append("<span class=\"pmoSelo pmoAcao").append(Escape.html(a.getAcao())).append("\">")
      .append(Escape.html(r.de(PRE_ACAO + a.getAcao())))
      .append("</span></p>");
    sb.append("<p class=\"pmoMotivoDecisao\">")
      .append(Escape.html(a.getMotivoDecisao())).append("</p>");

    if (!a.getRotulosGatilho().isEmpty()) {
      sb.append("<p class=\"pmoGatilhos\">")
        .append(Escape.html(r.de(CH_ANALISE_GATILHOS))).append(' ');
      for (int k = 0; k < a.getRotulosGatilho().size(); k++) {
        if (k > 0) {
          sb.append(", ");
        }
        sb.append(Escape.html(a.getRotulosGatilho().get(k)));
      }
      sb.append("</p>");
    }

    if (a.getAchados().isEmpty()) {
      // isLimpo(), e nao "lista vazia": um texto cortado no teto tambem chega
      // aqui sem achado, e dizer "nada encontrado" sobre ele seria a mentira
      // que a categoria NAO_VARRIDO existe para impedir. Nesse caso o alerta de
      // analise incompleta, ja' impresso acima, e' a resposta correta -- e aqui
      // nao se acrescenta nada.
      if (a.isLimpo()) {
        sb.append("<p class=\"pmoNota\">").append(Escape.html(r.de(CH_ANALISE_LIMPO)))
          .append("</p>");
      }
      sb.append("</div>");
      return;
    }

    sb.append("<table class=\"pmoTabela\"><thead><tr>")
      .append("<th>").append(Escape.html(r.de(CH_COL_ROTULO))).append("</th>")
      .append("<th>").append(Escape.html(r.de(CH_COL_SEVERIDADE))).append("</th>")
      .append("<th class=\"pmoNum\">").append(Escape.html(r.de(CH_COL_QUANTIDADE))).append("</th>")
      .append("<th>").append(Escape.html(r.de(CH_COL_AMOSTRAS))).append("</th>")
      .append("</tr></thead><tbody>");
    for (ResumoAnalise.AchadoSeguro achado : a.getAchados()) {
      sb.append("<tr>")
        .append("<td>").append(Escape.html(achado.getRotulo())).append("</td>")
        .append("<td><span class=\"pmoSelo pmoSev")
        .append(Escape.html(achado.getSeveridade())).append("\">")
        .append(Escape.html(r.de(PRE_SEVERIDADE + achado.getSeveridade())))
        .append("</span></td>")
        .append("<td class=\"pmoNum\">").append(achado.getQuantidade()).append("</td>")
        .append("<td class=\"pmoAmostras\">");
      List<String> amostras = achado.getAmostrasMascaradas();
      for (int k = 0; k < amostras.size(); k++) {
        if (k > 0) {
          sb.append(' ');
        }
        sb.append("<code>").append(Escape.html(amostras.get(k))).append("</code>");
      }
      sb.append("</td></tr>");
    }
    sb.append("</tbody></table>");
    sb.append("<p class=\"pmoNota\">").append(Escape.html(r.de(CH_AMOSTRAS_NOTA)))
      .append("</p>");
    sb.append("</div>");
  }

  /**
   * Percentual com duas casas.
   *
   * <p>{@link Locale#ROOT} de proposito: o numero fica igual em qualquer idioma
   * do navegador. Percentual que muda de separador conforme a lingua de quem
   * abre a tela e' percentual que nao se consegue comparar entre dois relatorios
   * -- e comparar dois relatorios e' o uso principal deste numero.
   */
  static String percentual(double valor) {
    return String.format(Locale.ROOT, "%.2f%%", valor);
  }
}
