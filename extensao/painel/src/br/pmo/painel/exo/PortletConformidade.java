package br.pmo.painel.exo;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.ResourceBundle;

import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.GenericPortlet;
import javax.portlet.PortletException;
import javax.portlet.PortletSession;
import javax.portlet.RenderRequest;
import javax.portlet.RenderResponse;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

import br.pmo.dlp.PoliticaDlp;
import br.pmo.dlp.RegrasSensiveis;
import br.pmo.dlp.RelatorioConformidade;
import br.pmo.dlp.Varredura;
import br.pmo.painel.AnaliseAoVivo;
import br.pmo.painel.PainelHtml;
import br.pmo.painel.ResumoAnalise;
import br.pmo.painel.Rotulos;

/**
 * A tela "Conformidade DLP" da administracao.
 *
 * <p><b>POR QUE UM PORTLET CLASSICO, E NAO UM APLICATIVO VUE.</b> Os portlets
 * novos da plataforma sao pacotes Vue montados por webpack. Aqui isso custaria
 * uma cadeia de compilacao de JavaScript -- npm, rede, node -- para renderizar
 * cinco tabelas que nao mudam sem recarregar a pagina. Renderizando no servidor,
 * o WAR sai de {@code javac} e {@code jar} e mais nada, a tela funciona com o
 * JavaScript do navegador desligado, e -- o que mais importa -- toda a montagem
 * do HTML fica em {@code br.pmo.painel}, que se prova no host antes de o WAR
 * existir. Um aplicativo Vue moveria justamente o escape de HTML para dentro de
 * um pacote que so' se testa com o portal de pe'.
 *
 * <p><b>ESTADO.</b> O portlet guarda um {@link RelatorioConformidade} proprio,
 * que acumula as analises feitas NESTA tela. Ele nao e' o relatorio do acervo:
 * o acumulador do conector vive dentro de {@code ConectorDlpRegex}, num campo
 * privado sem leitor publico, e portanto nao ha' como esta tela alcanca-lo sem
 * mexer naquele jar. O texto de abrangencia impresso no topo da tela diz isso ao
 * administrador, para o numero nao ser lido como retrato do acervo.
 *
 * <p><b>SEGURANCA.</b> Duas camadas, e a segunda existe porque a primeira e'
 * declarativa e editavel pela propria interface do portal:
 *
 * <ol>
 *   <li>a pagina declara {@code access-permissions *:/platform/administrators}
 *       em {@code pages.xml}, do mesmo jeito que as paginas de administracao da
 *       plataforma;</li>
 *   <li>este codigo confere a associacao ao grupo em CADA render e em CADA
 *       acao. Sem a segunda camada, alguem que consiga alterar a permissao da
 *       pagina -- ou colocar o portlet numa pagina qualquer -- ganharia junto a
 *       ferramenta de analise. A checagem na acao nao e' redundante com a do
 *       render: o POST e' um pedido separado e nao passa pelo render.</li>
 * </ol>
 */
public class PortletConformidade extends GenericPortlet {

  private static final Log LOG = ExoLogger.getExoLogger(PortletConformidade.class);

  /** Nome do campo do formulario. Tambem e' a chave do parametro da acao. */
  private static final String CAMPO_TEXTO = "pmoTextoParaAnalise";

  /** Chave da ultima analise na sessao do portlet. */
  private static final String SESSAO_ULTIMA = "pmo.painel.ultimaAnalise";

  private static final String PADRAO_GRUPO = "/platform/administrators";

  private static final int PADRAO_TETO_ENTRADA = 100_000;

  private static final String PADRAO_ARQUIVO_CSV = "conformidade-dlp.csv";

  private static final String PADRAO_TITULO = "Conformidade DLP — analises deste painel";

  private String grupoAdministradores;

  private String arquivoCsv;

  private int tetoEntrada;

  private RelatorioConformidade relatorio;

  private AnaliseAoVivo analise;

  /**
   * Le a configuracao dos {@code init-param} do {@code portlet.xml} e monta o
   * motor, a politica e o acumulador.
   *
   * <p>Falha de configuracao explode AQUI, na partida do portlet, e nao na
   * primeira vez que alguem abre a tela: o log do arranque e' lido, uma tela que
   * responde errado nao e'.
   */
  @Override
  public void init() throws PortletException {
    grupoAdministradores = texto("grupo.administradores", PADRAO_GRUPO);
    arquivoCsv = texto("arquivo.csv", PADRAO_ARQUIVO_CSV);
    tetoEntrada = inteiro("teto.caracteres.entrada", PADRAO_TETO_ENTRADA);

    Varredura varredura = new Varredura(
        inteiro("varredura.teto.caracteres", Varredura.TETO_CARACTERES_PADRAO),
        longo("varredura.teto.milissegundos", Varredura.TETO_MILISSEGUNDOS_PADRAO));

    PoliticaDlp politica = new PoliticaDlp(
        severidade("politica.severidade.minima", RegrasSensiveis.Severidade.ALTA),
        inteiro("politica.minimo.ocorrencias", 1),
        PoliticaDlp.Acao.de(getInitParameter("politica.acao"), PoliticaDlp.Acao.ALERTAR),
        PoliticaDlp.Acao.de(getInitParameter("politica.acao.incompleta"),
                            PoliticaDlp.Acao.ALERTAR),
        null);

    relatorio = new RelatorioConformidade(texto("titulo.relatorio", PADRAO_TITULO));
    analise = new AnaliseAoVivo(varredura, politica, relatorio, tetoEntrada);

    LOG.info("Painel de conformidade DLP iniciado: grupo=" + grupoAdministradores
             + ", teto de entrada=" + tetoEntrada + ", politica=" + politica);
  }

  /**
   * Monta a tela.
   *
   * <p>Escreve direto no {@code Writer} da resposta, sem despacho para JSP: nao
   * ha' JSP no WAR, e a montagem inteira ja' e' uma funcao pura de
   * {@code br.pmo.painel}.
   */
  @Override
  protected void doView(RenderRequest pedido, RenderResponse resposta)
      throws PortletException, IOException {
    resposta.setContentType("text/html; charset=UTF-8");
    Rotulos rotulos = rotulos(pedido);
    PrintWriter escritor = resposta.getWriter();

    if (!ehAdministrador()) {
      // Recusa explicita: pagina em branco pareceria defeito, e o painel sem os
      // numeros pareceria acervo limpo. Nenhum dos dois e' a verdade.
      escritor.write(PainelHtml.acessoNegado(rotulos, grupoAdministradores));
      return;
    }

    ResumoAnalise ultima = (ResumoAnalise) pedido.getPortletSession()
        .getAttribute(SESSAO_ULTIMA, PortletSession.PORTLET_SCOPE);

    escritor.write(PainelHtml.pagina(relatorio.instantaneo(),
                                     ultima,
                                     resposta.createActionURL().toString(),
                                     resposta.getNamespace(),
                                     CAMPO_TEXTO,
                                     arquivoCsv,
                                     tetoEntrada,
                                     rotulos));
  }

  /**
   * Recebe o texto colado, analisa e guarda o resumo para o render seguinte.
   *
   * <p>So' o {@link ResumoAnalise} vai para a sessao -- ver o javadoc dessa
   * classe. O texto colado e as ocorrencias cruas morrem ao fim deste metodo.
   */
  @Override
  public void processAction(ActionRequest pedido, ActionResponse resposta)
      throws PortletException, IOException {
    if (!ehAdministrador()) {
      // O POST e' um pedido proprio e nao passa pelo doView: sem esta linha, a
      // ferramenta de analise ficaria aberta a quem nao pode ver a tela.
      LOG.warn("Analise de texto recusada: usuario fora do grupo "
               + grupoAdministradores);
      return;
    }
    // getParameter devolve nulo quando o POST passou do limite do contentor.
    // AnaliseAoVivo trata nulo como caixa em branco, que e' a resposta certa:
    // nao ha' texto para analisar, e nada e' contabilizado.
    ResumoAnalise resumo = analise.analisar(pedido.getParameter(CAMPO_TEXTO));
    pedido.getPortletSession().setAttribute(SESSAO_ULTIMA, resumo,
                                            PortletSession.PORTLET_SCOPE);
  }

  // ===========================================================================
  // Identidade
  // ===========================================================================

  /**
   * Se quem esta' na requisicao pertence ao grupo de administradores.
   *
   * @return falso tambem quando NAO HA' identidade na requisicao. Ausencia de
   *         identidade e' o caso do usuario anonimo e o de um caminho de
   *         invocacao que ninguem previu; os dois tem de fechar a porta. Um
   *         {@code null} tratado como "provavelmente e' o administrador"
   *         transformaria esta tela em ferramenta publica de analise.
   */
  private boolean ehAdministrador() {
    ConversationState estado = ConversationState.getCurrent();
    if (estado == null) {
      return false;
    }
    Identity identidade = estado.getIdentity();
    if (identidade == null) {
      return false;
    }
    return identidade.isMemberOf(grupoAdministradores);
  }

  // ===========================================================================
  // Configuracao e idioma
  // ===========================================================================

  /**
   * Pacote de idioma para a lingua de quem abriu a tela.
   *
   * @return um {@link Rotulos} sempre utilizavel. Se o pacote nao carregar, ele
   *         vem com {@code null} dentro e cada rotulo sai como {@code !chave!}:
   *         a tela abre com os numeros certos e o defeito de idioma fica
   *         visivel, em vez de a tela inteira sumir por causa de um arquivo de
   *         traducao.
   */
  private Rotulos rotulos(RenderRequest pedido) {
    Locale locale = pedido.getLocale();
    try {
      return new Rotulos(getPortletConfig().getResourceBundle(
          locale == null ? Locale.getDefault() : locale));
    } catch (RuntimeException e) {
      LOG.warn("Nao foi possivel carregar o idioma do painel de conformidade", e);
      return new Rotulos(carregarDireto(locale));
    }
  }

  /**
   * Ultimo recurso: carrega o mesmo arquivo pelo {@link ResourceBundle} do JDK.
   *
   * <p>Existe porque o pacote do contentor de portlets pode nao estar disponivel
   * em toda via de invocacao, e perder TODOS os rotulos por causa disso seria
   * desproporcional.
   */
  private ResourceBundle carregarDireto(Locale locale) {
    try {
      return ResourceBundle.getBundle(Rotulos.NOME_BASE,
                                      locale == null ? Locale.getDefault() : locale,
                                      getClass().getClassLoader());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private String texto(String nome, String padrao) {
    String valor = getInitParameter(nome);
    return valor == null || valor.trim().isEmpty() ? padrao : valor.trim();
  }

  /**
   * Inteiro de configuracao.
   *
   * <p>Valor ilegivel ou nao positivo cai no padrao E fica no log. Deixar a
   * configuracao errada valer produziria uma tela que recusa todo texto sem
   * ninguem entender por que; cair no padrao em silencio esconderia o erro de
   * digitacao para sempre.
   */
  private int inteiro(String nome, int padrao) {
    String valor = getInitParameter(nome);
    if (valor == null || valor.trim().isEmpty()) {
      return padrao;
    }
    try {
      int lido = Integer.parseInt(valor.trim());
      if (lido <= 0) {
        LOG.warn("Parametro " + nome + "=" + valor + " nao e' positivo; usando " + padrao);
        return padrao;
      }
      return lido;
    } catch (NumberFormatException e) {
      LOG.warn("Parametro " + nome + "=" + valor + " nao e' inteiro; usando " + padrao);
      return padrao;
    }
  }

  private long longo(String nome, long padrao) {
    String valor = getInitParameter(nome);
    if (valor == null || valor.trim().isEmpty()) {
      return padrao;
    }
    try {
      long lido = Long.parseLong(valor.trim());
      if (lido <= 0) {
        LOG.warn("Parametro " + nome + "=" + valor + " nao e' positivo; usando " + padrao);
        return padrao;
      }
      return lido;
    } catch (NumberFormatException e) {
      LOG.warn("Parametro " + nome + "=" + valor + " nao e' inteiro; usando " + padrao);
      return padrao;
    }
  }

  private RegrasSensiveis.Severidade severidade(String nome,
                                                RegrasSensiveis.Severidade padrao) {
    String valor = getInitParameter(nome);
    if (valor == null || valor.trim().isEmpty()) {
      return padrao;
    }
    try {
      return RegrasSensiveis.Severidade.valueOf(valor.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      // Nao vira a severidade mais baixa nem a mais alta por engano: severidade
      // errada muda o que a politica dispara, e os dois extremos sao ruins --
      // um desliga o DLP, o outro alerta para tudo ate' alguem desligar o DLP.
      LOG.warn("Parametro " + nome + "=" + valor + " nao e' severidade; usando " + padrao);
      return padrao;
    }
  }
}
