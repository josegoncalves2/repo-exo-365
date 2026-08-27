package br.pmo.mfa.exo;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.mfa.api.MfaService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.web.filter.Filter;

import br.pmo.mfa.CatalogoZonas;
import br.pmo.mfa.OrigemRequisicao;
import br.pmo.mfa.Zona;

/**
 * Exige o segundo fator conforme a ZONA DE REDE de onde veio o acesso.
 *
 * <h2>O que a plataforma ja' fazia, e o que faltava</h2>
 *
 * O add-on {@code exo-multifactor-authentication} sabe exigir segundo fator por
 * GRUPO ({@code exo.mfa.protectedGroups}) e por AREA DE NAVEGACAO
 * ({@code exo.mfa.protectedGroupNavigations}). Nao ha' nada, em lugar nenhum
 * dele, que olhe o endereco de origem: {@code MfaService} tem exatamente dois
 * criterios de decisao, {@code isProtectedUri} e
 * {@code currentUserIsInProtectedGroup}. "2FA por zona" simplesmente nao
 * existia.
 *
 * <h2>O que este filtro NAO faz, de proposito</h2>
 *
 * Nao gera codigo, nao guarda segredo, nao desenha tela e nao valida OTP. Tudo
 * isso ja' existe e funciona no add-on. Este filtro so' RESPONDE A PERGUNTA
 * "este acesso, vindo daqui, precisa de segundo fator?" e, quando a resposta e'
 * sim, manda o usuario para a MESMA tela
 * ({@code /portal/dw/mfa-access}) e reconhece a MESMA marca de sessao
 * ({@code mfaValidated} / {@code mfaExpiration}) que o add-on grava.
 *
 * <p>A consequencia pratica e' que quem cadastrou o segundo fator continua com
 * um so' fluxo, um so' aplicativo autenticador e uma so' tela ; e nao existe um
 * segundo cadastro paralelo para manter em dia.
 *
 * <h2>Onde mora o perigo</h2>
 *
 * Descobrir a origem atras do nginx e' a parte perigosa, e esta' isolada em
 * {@link OrigemRequisicao}. Ler {@code X-Forwarded-For} de forma ingenua
 * transformaria esta funcionalidade no seu proprio contrario: qualquer usuario
 * mandaria um cabecalho se declarando na rede interna e ficaria dispensado do
 * segundo fator. So' se confia no que a NOSSA borda anotou.
 *
 * <h2>Estado inerte</h2>
 *
 * Sem {@code exo.mfa.zonas.exigir} preenchido, este filtro nao opina sobre
 * nada e o comportamento e' identico ao de antes de ele existir. Ligar
 * exigencia global antes de os administradores terem cadastrado o segundo
 * fator TRANCA o portal ; e quem tem a chave para reverter e' justamente quem
 * ficou de fora.
 */
public class FiltroMfaPorZona implements Filter {

  private static final Log LOG = ExoLogger.getExoLogger(FiltroMfaPorZona.class);

  /** Tela do proprio add-on. Reaproveitada de proposito. */
  private static final String URI_SEGUNDO_FATOR = "/portal/dw/mfa-access";

  /** Marcas de sessao gravadas pelo add-on quando o OTP e' aceito. */
  private static final String SESSAO_VALIDADO = "mfaValidated";
  private static final String SESSAO_EXPIRACAO = "mfaExpiration";
  private static final String SESSAO_URI_INICIAL = "initialUri";

  /**
   * Recursos que nao podem ser redirecionados. Redirecionar folha de estilo ou
   * chamada REST quebra a propria tela de segundo fator ; o usuario veria uma
   * pagina sem CSS e sem conseguir enviar o codigo.
   */
  private static final List<String> PREFIXOS_LIVRES =
      Collections.unmodifiableList(Arrays.asList("/portal/javascript",
                                                 "/portal/rest",
                                                 "/portal/scripts",
                                                 "/portal/skins",
                                                 "/portal/service-worker.js",
                                                 URI_SEGUNDO_FATOR));

  /**
   * Configuracao interpretada uma vez. {@code volatile} porque o filtro e'
   * concorrente por natureza: sem isso, uma requisicao poderia ver o objeto
   * pela metade, com as listas ainda nao publicadas.
   */
  private volatile Configuracao configuracao;

  private static final class Configuracao {
    private final CatalogoZonas catalogo;
    private final OrigemRequisicao origem;
    private final boolean valida;

    Configuracao(CatalogoZonas catalogo, OrigemRequisicao origem, boolean valida) {
      this.catalogo = catalogo;
      this.origem = origem;
      this.valida = valida;
    }
  }

  private Configuracao obterConfiguracao() {
    Configuracao atual = configuracao;
    if (atual != null) {
      return atual;
    }
    synchronized (this) {
      if (configuracao == null) {
        configuracao = interpretar();
      }
      return configuracao;
    }
  }

  private Configuracao interpretar() {
    String exigir = PropertyManager.getProperty("exo.mfa.zonas.exigir");
    String isentar = PropertyManager.getProperty("exo.mfa.zonas.isentar");
    String proxies = PropertyManager.getProperty("exo.mfa.zonas.proxiesConfiaveis");
    String indeterminado = PropertyManager.getProperty("exo.mfa.zonas.quandoIndeterminado");

    try {
      List<Zona> zonasProxy = CatalogoZonas.interpretarLista(proxies);
      CatalogoZonas catalogo = new CatalogoZonas(
          CatalogoZonas.interpretarLista(exigir),
          CatalogoZonas.interpretarLista(isentar),
          CatalogoZonas.QuandoIndeterminado.de(
              indeterminado, CatalogoZonas.QuandoIndeterminado.EXIGIR));

      if (catalogo.estaInerte()) {
        LOG.info("2FA por zona: INERTE (exo.mfa.zonas.exigir vazio); nada muda");
      } else {
        LOG.info("2FA por zona: exigir={} isentar={} proxiesConfiaveis={} quandoIndeterminado={}",
                 catalogo.getExigir(), catalogo.getIsentar(), zonasProxy,
                 catalogo.getQuandoIndeterminado());
        if (zonasProxy.isEmpty()) {
          // Nao e' erro, mas quase sempre e' engano: atras do nginx, sem proxy
          // declarado, TODA requisicao aparece com o endereco do proxy e cai
          // sempre na mesma zona.
          LOG.warn("2FA por zona: nenhum proxy confiavel declarado em "
                   + "exo.mfa.zonas.proxiesConfiaveis. Atras de proxy reverso, toda "
                   + "requisicao sera' julgada pelo endereco do PROXY, e nao pelo do usuario.");
        }
      }
      return new Configuracao(catalogo, new OrigemRequisicao(zonasProxy), true);

    } catch (IllegalArgumentException e) {
      // Faixa escrita errada. NAO se assume uma politica pela metade: marca-se
      // invalida e o filtro se abstem, deixando a decisao com o mecanismo por
      // grupo do add-on. Aplicar metade de uma regra de rede e' pior do que
      // nao aplicar ; produz isencao onde o administrador acredita ter
      // exigencia.
      LOG.error("2FA por zona DESLIGADO: configuracao de faixas invalida ({}). "
                + "Corrija exo.mfa.zonas.* e reinicie. Ate' la', a decisao fica "
                + "inteiramente com o mecanismo por grupo do add-on.", e.getMessage());
      return new Configuracao(null, null, false);
    }
  }

  @Override
  public void doFilter(ServletRequest requisicao, ServletResponse resposta, FilterChain corrente)
      throws IOException, ServletException {

    if (!(requisicao instanceof HttpServletRequest)
        || !(resposta instanceof HttpServletResponse)) {
      corrente.doFilter(requisicao, resposta);
      return;
    }
    HttpServletRequest req = (HttpServletRequest) requisicao;
    HttpServletResponse res = (HttpServletResponse) resposta;

    try {
      if (deveIntervir(req)) {
        HttpSession sessao = req.getSession(true);
        sessao.setAttribute(SESSAO_URI_INICIAL, uriCompleta(req));
        res.sendRedirect(req.getContextPath() == null
                         ? URI_SEGUNDO_FATOR
                         : URI_SEGUNDO_FATOR);
        return;
      }
    } catch (RuntimeException e) {
      // Filtro de seguranca que estoura NAO pode derrubar o portal inteiro.
      // Registra e deixa passar: o mecanismo por grupo do add-on continua
      // valendo, e o pior caso e' o comportamento anterior a esta extensao.
      LOG.error("2FA por zona: falha ao decidir; seguindo sem intervir", e);
    }

    corrente.doFilter(requisicao, resposta);
  }

  /** A decisao inteira, isolada para poder ser lida de uma vez. */
  private boolean deveIntervir(HttpServletRequest req) {
    Configuracao conf = obterConfiguracao();
    if (!conf.valida || conf.catalogo.estaInerte()) {
      return false;
    }

    // Usuario ainda nao autenticado: nao ha' segundo fator a exigir, e mandar
    // um anonimo para a tela de OTP so' produziria laco de redirecionamento.
    if (req.getRemoteUser() == null) {
      return false;
    }

    String uri = req.getRequestURI();
    if (uri == null) {
      return false;
    }
    for (String livre : PREFIXOS_LIVRES) {
      if (uri.startsWith(livre)) {
        return false;
      }
    }

    // Se o proprio recurso de MFA esta' desligado na plataforma, a tela de
    // segundo fator nao opera ; redirecionar para ela trancaria o usuario.
    MfaService mfa = CommonsUtils.getService(MfaService.class);
    if (mfa == null || !mfa.isMfaFeatureActivated()) {
      return false;
    }

    if (jaValidouNestaSessao(req.getSession(false))) {
      return false;
    }

    String enderecoOrigem = conf.origem.resolver(req.getRemoteAddr(), cabecalhoDeOrigem(req));
    CatalogoZonas.Decisao decisao = conf.catalogo.decidir(enderecoOrigem);

    if (decisao.exigeSegundoFator()) {
      // Nivel INFO e nao DEBUG: exigir segundo fator e' evento de auditoria, e
      // o motivo precisa estar no registro para alguem poder contestar depois.
      LOG.info("2FA por zona: exigindo segundo fator de '{}' ; {}",
               req.getRemoteUser(), decisao.getMotivo());
      return true;
    }
    return false;
  }

  /**
   * O add-on grava estas marcas quando o OTP e' aceito. Le-las (em vez de
   * inventar marca propria) e' o que faz um unico fluxo servir aos dois
   * criterios ; o usuario nao valida duas vezes.
   */
  private boolean jaValidouNestaSessao(HttpSession sessao) {
    if (sessao == null) {
      return false;
    }
    Object validado = sessao.getAttribute(SESSAO_VALIDADO);
    if (!(validado instanceof Boolean) || !((Boolean) validado)) {
      return false;
    }
    Object expiracao = sessao.getAttribute(SESSAO_EXPIRACAO);
    if (expiracao instanceof Long) {
      // Expirada conta como NAO validada. Aceitar marca vencida seria manter a
      // sessao dispensada para sempre depois de um unico OTP.
      return System.currentTimeMillis() < (Long) expiracao;
    }
    // Sem expiracao gravada, vale a marca. E' o comportamento do add-on, e
    // divergir dele produziria exigencia que o usuario nao consegue satisfazer.
    return true;
  }

  private static String cabecalhoDeOrigem(HttpServletRequest req) {
    for (String nome : OrigemRequisicao.cabecalhosConsultados()) {
      String valor = req.getHeader(nome);
      if (valor != null && !valor.trim().isEmpty()) {
        return valor;
      }
    }
    return null;
  }

  /** URI com a query, para o add-on devolver o usuario ao lugar certo. */
  private static String uriCompleta(HttpServletRequest req) {
    String uri = req.getRequestURI();
    String consulta = req.getQueryString();
    return (consulta == null || consulta.isEmpty()) ? uri : uri + "?" + consulta;
  }
}
