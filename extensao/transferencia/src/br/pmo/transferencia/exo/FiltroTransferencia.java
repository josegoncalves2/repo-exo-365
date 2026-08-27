package br.pmo.transferencia.exo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.web.filter.Filter;

import br.pmo.mfa.Zona;
import br.pmo.transferencia.Pedido;
import br.pmo.transferencia.PoliticaTransferencia;
import br.pmo.transferencia.Regra;

/**
 * Aplica as restricoes avancadas de download e compartilhamento.
 *
 * <h2>O que a plataforma ja' fazia</h2>
 *
 * O nativo e' {@code TransferRulesRestService}, e sao DOIS INTERRUPTORES
 * GLOBAIS guardados no SettingService: {@code sharedDocumentStatus} e
 * {@code downloadDocumentStatus}. Liga e desliga, para o portal inteiro, para
 * todo mundo. Nao ha' nada por grupo, por espaco, por tipo de arquivo, por
 * tamanho ou por rede.
 *
 * O briefing pede restricao "com base em permissoes e politicas da empresa".
 * Dois interruptores globais nao sao isso: sao um disjuntor.
 *
 * <h2>Modo observacao, e por que ele nao e' preguica</h2>
 *
 * As rotas por onde um arquivo efetivamente sai do portal nao estao declaradas
 * em lugar nenhum que se possa ler com certeza: no eXo 7.2.1 tudo e' mapeado em
 * {@code /*} e o caminho real depende de qual portlet, qual conector e qual
 * versao de front-end fez o pedido.
 *
 * Bloquear com base em SUPOSICAO sobre essas rotas erra dos dois lados ao mesmo
 * tempo: deixa passar o que se queria barrar (rota que nao foi prevista) e
 * barra o que deveria passar (rota parecida que nao era download). E os dois
 * erros sao silenciosos.
 *
 * Por isso o filtro NASCE EM OBSERVACAO: avalia, registra o que veria, e nao
 * impede nada. O administrador le o registro, ve as rotas reais do SEU trafego,
 * ajusta os padroes e SO' ENTAO passa para APLICACAO. E' levantamento antes de
 * enforcement, que e' a ordem certa e nao a comoda.
 *
 * <h2>Limite declarado: tamanho</h2>
 *
 * O tamanho do arquivo nao e' conhecido no momento em que a requisicao chega ;
 * so' quando a resposta comeca a ser escrita. Regras com
 * {@code aPartirDeBytes} existem no motor e sao uteis para quem chama a
 * politica JA' SABENDO o tamanho (uma acao JCR, por exemplo), mas neste filtro
 * elas nunca casam, porque aqui o tamanho e' desconhecido. Isso esta' dito em
 * vez de escondido: uma regra de tamanho configurada aqui NAO protege nada, e
 * o administrador precisa saber disso.
 */
public class FiltroTransferencia implements Filter {

  private static final Log LOG = ExoLogger.getExoLogger(FiltroTransferencia.class);

  /**
   * Padroes de URI considerados transferencia, quando nada e' configurado.
   * Sao ponto de PARTIDA para o levantamento, nao verdade estabelecida.
   */
  private static final String PADROES_PADRAO =
      "/portal/rest/jcr/,/portal/rest/private/jcr/,/portal/download,"
      + "/portal/rest/v1/documents/download,/portal/rest/documents/download,"
      + "/rest/jcr/,/portal/rest/wcmDriver/,/portal/rest/contents/";

  private volatile Configuracao configuracao;

  private static final class Configuracao {
    private final PoliticaTransferencia politica;
    private final List<String> padroesUri;
    private final boolean valida;

    Configuracao(PoliticaTransferencia politica, List<String> padroesUri, boolean valida) {
      this.politica = politica;
      this.padroesUri = padroesUri;
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
    try {
      List<String> padroes = separar(
          valorOu("exo.transferencia.padroesUri", PADROES_PADRAO));

      PoliticaTransferencia.Modo modo = PoliticaTransferencia.Modo.de(
          PropertyManager.getProperty("exo.transferencia.modo"),
          PoliticaTransferencia.Modo.OBSERVACAO);

      Regra.Efeito padrao = "NEGAR".equalsIgnoreCase(
          valorOu("exo.transferencia.acaoPadrao", "PERMITIR"))
          ? Regra.Efeito.NEGAR : Regra.Efeito.PERMITIR;

      List<Regra> regras = montarRegras();
      PoliticaTransferencia politica = new PoliticaTransferencia(regras, padrao, modo);

      if (politica.estaInerte()) {
        LOG.info("Transferencia: INERTE (nenhuma regra e acao padrao PERMITIR); nada muda");
      } else {
        LOG.info("Transferencia: modo={} acaoPadrao={} regras={} padroesUri={}",
                 modo, padrao, politica.getRegras(), padroes);
        if (modo == PoliticaTransferencia.Modo.OBSERVACAO) {
          LOG.info("Transferencia: em OBSERVACAO nada e' impedido. Leia os registros "
                   + "'Transferencia: NEGARIA' para conhecer as rotas reais antes de "
                   + "passar exo.transferencia.modo para APLICACAO.");
        }
      }
      return new Configuracao(politica, padroes, true);

    } catch (IllegalArgumentException e) {
      // Faixa de rede escrita errada numa regra. Politica pela metade e' pior
      // do que politica nenhuma: aplica-se onde o administrador nao espera e
      // deixa de aplicar onde ele acredita estar coberto.
      LOG.error("Transferencia DESLIGADA: configuracao invalida ({}). "
                + "Corrija exo.transferencia.* e reinicie.", e.getMessage());
      return new Configuracao(null, Collections.<String>emptyList(), false);
    }
  }

  /**
   * Le as regras de propriedades numeradas:
   *
   * <pre>
   *   exo.transferencia.regra.1.nome=ti-pode-pst
   *   exo.transferencia.regra.1.efeito=PERMITIR
   *   exo.transferencia.regra.1.extensoes=pst,ost
   *   exo.transferencia.regra.1.grupos=/platform/ti
   *   exo.transferencia.regra.1.excetoGrupos=
   *   exo.transferencia.regra.1.zonas=192.168.1.0/24
   *   exo.transferencia.regra.1.operacoes=BAIXAR,COMPARTILHAR
   *   exo.transferencia.regra.1.motivo=TI precisa para migracao
   * </pre>
   *
   * <p>A NUMERACAO E' A ORDEM DE AVALIACAO, e isso e' contrato: primeira que
   * casa decide. Por isso a leitura para no primeiro numero ausente ; um
   * buraco na sequencia (1, 2, 4) seria uma regra que o administrador escreveu
   * e que silenciosamente nunca seria avaliada.
   */
  private List<Regra> montarRegras() {
    List<Regra> regras = new ArrayList<>();
    for (int i = 1; i <= 200; i++) {
      String base = "exo.transferencia.regra." + i + ".";
      String nome = PropertyManager.getProperty(base + "nome");
      if (nome == null || nome.trim().isEmpty()) {
        break;
      }
      Regra.Efeito efeito = "PERMITIR".equalsIgnoreCase(
          valorOu(base + "efeito", "NEGAR")) ? Regra.Efeito.PERMITIR : Regra.Efeito.NEGAR;

      Regra.Construtor construtor = Regra.nomeada(nome, efeito)
          .comMotivo(PropertyManager.getProperty(base + "motivo"));

      List<String> extensoes = separar(PropertyManager.getProperty(base + "extensoes"));
      if (!extensoes.isEmpty()) {
        construtor.paraExtensoes(extensoes.toArray(new String[0]));
      }
      List<String> grupos = separar(PropertyManager.getProperty(base + "grupos"));
      if (!grupos.isEmpty()) {
        construtor.paraGrupos(grupos.toArray(new String[0]));
      }
      List<String> excetoGrupos = separar(PropertyManager.getProperty(base + "excetoGrupos"));
      if (!excetoGrupos.isEmpty()) {
        construtor.excetoGrupos(excetoGrupos.toArray(new String[0]));
      }
      List<String> zonasTexto = separar(PropertyManager.getProperty(base + "zonas"));
      if (!zonasTexto.isEmpty()) {
        List<Zona> zonas = new ArrayList<>();
        for (String z : zonasTexto) {
          zonas.add(Zona.de(z));
        }
        construtor.paraZonas(zonas);
      }
      for (String op : separar(PropertyManager.getProperty(base + "operacoes"))) {
        try {
          construtor.paraOperacoes(Pedido.Operacao.valueOf(op.toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException(
              "operacao invalida '" + op + "' em " + base + "operacoes");
        }
      }
      regras.add(construtor.construir());
    }
    return regras;
  }

  private static String valorOu(String chave, String padrao) {
    String valor = PropertyManager.getProperty(chave);
    return (valor == null || valor.trim().isEmpty()) ? padrao : valor.trim();
  }

  private static List<String> separar(String csv) {
    List<String> itens = new ArrayList<>();
    if (csv == null) {
      return itens;
    }
    for (String pedaco : csv.split(",")) {
      String limpo = pedaco.trim();
      if (!limpo.isEmpty()) {
        itens.add(limpo);
      }
    }
    return itens;
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
      Configuracao conf = obterConfiguracao();
      if (conf.valida && !conf.politica.estaInerte() && ehTransferencia(req, conf)) {

        Pedido pedido = montarPedido(req);
        PoliticaTransferencia.Decisao decisao = conf.politica.decidir(pedido);

        if (!decisao.isPermitido()) {
          if (decisao.impedeDeFato()) {
            LOG.info("Transferencia: IMPEDIDA {} ; {}", pedido, decisao);
            res.sendError(HttpServletResponse.SC_FORBIDDEN, decisao.getMotivo());
            return;
          }
          // Modo observacao: o pedido segue, e o registro diz o que aconteceria.
          // E' este registro que revela as rotas reais antes de qualquer bloqueio.
          LOG.info("Transferencia: NEGARIA (observacao, nada impedido) {} ; {}", pedido, decisao);
        }
      }
    } catch (RuntimeException e) {
      // Filtro de politica que estoura NAO pode derrubar o download de todo
      // mundo. Registra e deixa passar; o pior caso e' o comportamento
      // anterior a esta extensao.
      LOG.error("Transferencia: falha ao decidir; seguindo sem intervir", e);
    }

    corrente.doFilter(requisicao, resposta);
  }

  private boolean ehTransferencia(HttpServletRequest req, Configuracao conf) {
    String uri = req.getRequestURI();
    if (uri == null) {
      return false;
    }
    for (String padrao : conf.padroesUri) {
      if (uri.startsWith(padrao)) {
        return true;
      }
    }
    return false;
  }

  private Pedido montarPedido(HttpServletRequest req) {
    String usuario = req.getRemoteUser();
    Set<String> grupos = new LinkedHashSet<>();

    ConversationState estado = ConversationState.getCurrent();
    if (estado != null) {
      Identity identidade = estado.getIdentity();
      if (identidade != null) {
        if (usuario == null) {
          usuario = identidade.getUserId();
        }
        Set<String> doUsuario = identidade.getGroups();
        if (doUsuario != null) {
          grupos.addAll(doUsuario);
        }
      }
    }

    Pedido.Operacao operacao = operacaoDe(req);

    return new Pedido(usuario,
                      grupos,
                      req.getRequestURI(),
                      nomeArquivoDe(req.getRequestURI()),
                      // Tamanho DESCONHECIDO nesta camada. Ver o javadoc da
                      // classe: regras de tamanho nao casam aqui, de proposito,
                      // e isso esta' declarado em vez de escondido.
                      -1L,
                      req.getRemoteAddr(),
                      operacao);
  }

  /**
   * Compartilhamento e download chegam pela mesma familia de rotas; o que os
   * distingue de forma confiavel e' o METODO. GET/HEAD retiram conteudo;
   * POST/PUT sobre as mesmas rotas costumam criar link ou permissao.
   */
  private static Pedido.Operacao operacaoDe(HttpServletRequest req) {
    String metodo = req.getMethod();
    if (metodo == null || "GET".equalsIgnoreCase(metodo) || "HEAD".equalsIgnoreCase(metodo)) {
      return Pedido.Operacao.BAIXAR;
    }
    return Pedido.Operacao.COMPARTILHAR;
  }

  private static String nomeArquivoDe(String uri) {
    if (uri == null) {
      return null;
    }
    // Ignora parametro de matriz (;jsessionid=...) antes de tirar o ultimo
    // segmento: sem isso o "nome do arquivo" viria com o identificador de
    // sessao colado e nenhuma regra de extensao casaria.
    String limpo = uri;
    int pontoVirgula = limpo.indexOf(';');
    if (pontoVirgula >= 0) {
      limpo = limpo.substring(0, pontoVirgula);
    }
    int barra = limpo.lastIndexOf('/');
    return barra >= 0 && barra < limpo.length() - 1 ? limpo.substring(barra + 1) : limpo;
  }
}
