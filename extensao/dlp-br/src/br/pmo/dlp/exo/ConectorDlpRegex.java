package br.pmo.dlp.exo;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;

import org.exoplatform.commons.search.index.IndexingService;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ValueParam;
import org.exoplatform.dlp.connector.FileDlpConnector;
import org.exoplatform.dlp.processor.DlpOperationProcessor;
import org.exoplatform.dlp.service.RestoredDlpItemService;
import org.exoplatform.ecms.legacy.search.data.SearchResult;
import org.exoplatform.services.cms.documents.TrashService;
import org.exoplatform.services.cms.link.LinkManager;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.core.ExtendedSession;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.wcm.search.connector.FileSearchServiceConnector;
import org.exoplatform.services.wcm.utils.WCMCoreUtils;

import br.pmo.dlp.Achado;
import br.pmo.dlp.Extrator;
import br.pmo.dlp.ExtratorTextoSimples;
import br.pmo.dlp.PoliticaDlp;
import br.pmo.dlp.RegrasSensiveis.Severidade;
import br.pmo.dlp.ResultadoVarredura;
import br.pmo.dlp.Varredura;

/**
 * Conector DLP que acrescenta deteccao por PADRAO (CPF, CNPJ, cartao, PIS,
 * titulo, CNH, chave PIX, segredo em texto claro) a' deteccao por palavra-chave
 * que o add-on exo-dlp ja' faz.
 *
 * <h2>Por que HERDAR de FileDlpConnector, e nao escrever um conector novo</h2>
 *
 * A tentacao e' implementar {@code DlpServiceConnector} do zero. Seria errado
 * por dois motivos medidos no proprio add-on:
 *
 * <ol>
 *   <li><b>Conector de tipo novo nunca receberia item algum.</b>
 *       {@code FileDLPAction} enfileira com o tipo FIXO {@code "file"}
 *       ({@code queueDlpService.addToQueue(entityId, "file")}), e o processador
 *       despacha por esse tipo. Um conector registrado como "regex" seria
 *       codigo morto -- compilado, implantado e jamais chamado.</li>
 *   <li><b>A mecanica de quarentena e' delicada e ja' esta' validada.</b> Mover
 *       o no' para /Quarantine, gravar {@code DlpPositiveItemEntity},
 *       reindexar no Elasticsearch, guardar o caminho de restauracao, tratar
 *       symlink e lixeira -- sao ~400 linhas de JCR na classe da eXo.
 *       Reimplementar isso e' assumir a chance de quebrar a RESTAURACAO, e
 *       documento que entra em quarentena e nao volta e' perda de dado.</li>
 * </ol>
 *
 * Herdar resolve os dois: mantem-se o tipo {@code "file"} (recebe a fila real)
 * e delega-se a {@code treatItem(String, Collection)}, que e' {@code protected}
 * na classe da eXo -- ou seja, extensao PREVISTA, nao gambiarra.
 *
 * <h2>Ordem de decisao</h2>
 *
 * <pre>
 *   processItem(id)
 *     |- extrai texto do no' (texto puro -> Tika)
 *     |- Varredura: quais padroes ocorrem, e quantas vezes
 *     |- PoliticaDlp: o que fazer com isso
 *     |    |- acao impeditiva (BLOQUEAR/QUARENTENAR) -> treatItem(...) e encerra
 *     |    +- demais acoes -> registra no log e SEGUE
 *     +- super.processItem(id)  <- palavra-chave nativa continua valendo
 * </pre>
 *
 * <h2>Modo de falhar</h2>
 *
 * Qualquer erro deste conector cai em {@code super.processItem(id)}. O pior
 * cenario e' o DLP se comportar exatamente como se comportava antes desta
 * extensao existir -- nunca pior. Isso e' deliberado: uma extensao de seguranca
 * que derruba o processamento do acervo inteiro causa mais dano do que a
 * exposicao que pretendia evitar.
 *
 * <h2>Padrao seguro</h2>
 *
 * {@link PoliticaDlp#padrao()} nasce em ALERTAR, nao em QUARENTENAR. Ligar
 * quarentena automatica por regex num acervo em producao, sem antes medir o
 * volume de achados, retiraria documento legitimo de circulacao em massa. A
 * ordem correta e' observar o laudo, ajustar isencoes e SO' ENTAO endurecer --
 * o que se faz pelo init-param {@code dlp.regex.acao}, sem recompilar nada.
 */
public class ConectorDlpRegex extends FileDlpConnector {

  private static final Log LOG = ExoLogger.getExoLogger(ConectorDlpRegex.class);

  /** Workspace onde vivem os documentos do portal. Mesmo valor que a constante
   *  privada COLLABORATION_WS da classe da eXo. */
  private static final String WORKSPACE = "collaboration";

  private static final String NO_CONTEUDO = "jcr:content";
  private static final String PROP_DADOS = "jcr:data";
  private static final String PROP_MIME = "jcr:mimeType";
  private static final String PROP_TITULO = "exo:title";

  private final RepositoryService servicoRepositorio;
  private final DlpOperationProcessor processadorDlp;
  private final Varredura varredura;
  private final PoliticaDlp politica;
  private final boolean ligado;
  private final long tetoBytesArquivo;

  /** Extratores em ordem de custo: o barato responde primeiro, o generalista
   *  (Tika) fica por ultimo. */
  private final List<Extrator> extratores = new ArrayList<>();

  public ConectorDlpRegex(InitParams parametros,
                          FileSearchServiceConnector conectorBusca,
                          RepositoryService servicoRepositorio,
                          IndexingService servicoIndexacao,
                          DlpOperationProcessor processador,
                          RestoredDlpItemService servicoRestaurados,
                          LinkManager gerenteLinks,
                          TrashService servicoLixeira) {
    super(parametros, conectorBusca, servicoRepositorio, servicoIndexacao,
          processador, servicoRestaurados, gerenteLinks, servicoLixeira);
    this.servicoRepositorio = servicoRepositorio;
    this.processadorDlp = processador;

    this.ligado = lerBooleano(parametros, "dlp.regex.enabled", true);
    Severidade corte = RegrasSensiveis_severidade(
        lerTexto(parametros, "dlp.regex.severidadeMinima", null));
    PoliticaDlp.Acao acao = PoliticaDlp.Acao.de(
        lerTexto(parametros, "dlp.regex.acao", null), PoliticaDlp.Acao.ALERTAR);
    int minimo = (int) lerInteiro(parametros, "dlp.regex.minimoOcorrencias", 1);
    this.tetoBytesArquivo = lerInteiro(parametros, "dlp.regex.tetoBytesArquivo",
                                       16L * 1024 * 1024);

    this.politica = new PoliticaDlp(corte, minimo, acao, PoliticaDlp.Acao.ALERTAR, null);
    this.varredura = new Varredura();

    this.extratores.add(new ExtratorTextoSimples());
    this.extratores.add(new ExtratorTika());

    LOG.info("DLP por padrao: ligado={} severidadeMinima={} acao={} minimoOcorrencias={} tetoBytesArquivo={}",
             this.ligado, corte, acao, minimo, this.tetoBytesArquivo);
  }

  private static Severidade RegrasSensiveis_severidade(String texto) {
    return br.pmo.dlp.RegrasSensiveis.severidadeDe(texto, Severidade.ALTA);
  }

  @Override
  public boolean processItem(String entityId) {
    if (!ligado) {
      return delegarAoNativo(entityId);
    }
    try {
      String texto = extrairTexto(entityId);
      if (texto != null && !texto.isEmpty()) {
        ResultadoVarredura resultado = varredura.varrer(texto);
        PoliticaDlp.Decisao decisao = politica.decidir(resultado);

        if (decisao.impedeOperacao()) {
          LOG.info("DLP por padrao RETIROU DE CIRCULACAO item={} acao={} classificacao={} motivo={}",
                   entityId, decisao.getAcao(), decisao.getClassificacao(), decisao.getMotivo());
          // Delega a MECANICA (quarentena, reindexacao, caminho de restauracao)
          // para a implementacao da eXo, ja' validada.
          treatItem(entityId, sintetizarResultados(resultado));
          return true;
        }

        if (!resultado.isLimpo()) {
          // Achou, mas a politica nao manda tirar do ar. Fica no log, que e' a
          // base do relatorio de conformidade -- e a evidencia de que o DLP
          // esta' vendo o acervo, e nao apenas instalado.
          LOG.info("DLP por padrao REGISTROU item={} acao={} classificacao={} {}",
                   entityId, decisao.getAcao(), decisao.getClassificacao(), resultado.resumo());
        }
      }
    } catch (Exception e) {
      // Nunca deixa a excecao subir: ela abortaria o lote inteiro do
      // DlpOperationProcessor, e um arquivo problematico pararia a varredura
      // de todos os outros.
      LOG.error("DLP por padrao falhou no item {} - seguindo com a deteccao nativa por palavra-chave",
                entityId, e);
    }
    return delegarAoNativo(entityId);
  }

  /**
   * Delegacao GUARDADA para a deteccao nativa por palavra-chave.
   *
   * <p><b>ESTA GUARDA IMPEDE UM DESASTRE.</b> {@code FileDlpConnector} nao
   * confere se ha' palavras-chave configuradas ; quem conferia era
   * {@code FileDLPAction}, do lado de fora, antes de enfileirar. Como a
   * extensao passou a enfileirar por conta propria (para que a deteccao por
   * padrao nao dependa de palavra-chave nenhuma), essa conferencia externa
   * deixou de acontecer.
   *
   * <p>Sem esta guarda, com {@code exo.dlp.keywords} vazio ; que e' o estado
   * desta instalacao ; a busca no Elasticsearch seria feita com termo vazio,
   * que tende a casar com TUDO, e o acervo inteiro iria para a quarentena.
   *
   * <p>Sem palavras cadastradas, portanto, o item e' dado por tratado: nao ha'
   * o que procurar, e devolver "tratado" e' o que o retira da fila.
   */
  private boolean delegarAoNativo(String entityId) {
    String palavras = processadorDlp == null ? null : processadorDlp.getKeywords();
    if (palavras == null || palavras.trim().isEmpty()) {
      return true;
    }
    return super.processItem(entityId);
  }

  /**
   * Texto do no', para a varredura: titulo, nome e conteudo binario.
   *
   * <p>Titulo e nome entram porque "Relacao de CPF dos servidores.xlsx" ja'
   * denuncia o conteudo, e porque ha' formato cujo binario o Tika nao abre.
   */
  private String extrairTexto(String entityId) throws Exception {
    Session sessao = WCMCoreUtils.getSystemSessionProvider()
                                 .getSession(WORKSPACE,
                                             servicoRepositorio.getCurrentRepository());
    Node no;
    try {
      no = ((ExtendedSession) sessao).getNodeByIdentifier(entityId);
    } catch (javax.jcr.ItemNotFoundException e) {
      // Item removido entre o enfileiramento e a varredura. Nao e' erro.
      return null;
    }
    if (no == null) {
      return null;
    }

    StringBuilder texto = new StringBuilder();
    texto.append(no.getName()).append('\n');
    if (no.hasProperty(PROP_TITULO)) {
      texto.append(no.getProperty(PROP_TITULO).getString()).append('\n');
    }

    if (!no.hasNode(NO_CONTEUDO)) {
      return texto.toString();
    }
    Node conteudo = no.getNode(NO_CONTEUDO);
    if (!conteudo.hasProperty(PROP_DADOS)) {
      return texto.toString();
    }

    long tamanho = conteudo.getProperty(PROP_DADOS).getLength();
    if (tamanho > tetoBytesArquivo) {
      // Arquivo grande demais para extrair com seguranca de memoria. NAO e'
      // tratado como limpo: o nome e o titulo ja' foram varridos, e o caso
      // fica no log para a administracao decidir.
      LOG.info("DLP por padrao: item {} tem {} bytes, acima do teto de {} - varrido so' por nome/titulo",
               entityId, tamanho, tetoBytesArquivo);
      return texto.toString();
    }

    String mime = conteudo.hasProperty(PROP_MIME)
                  ? conteudo.getProperty(PROP_MIME).getString()
                  : null;

    for (Extrator extrator : extratores) {
      if (!extrator.aceita(no.getName(), mime)) {
        continue;
      }
      // try-with-resources: o contrato do Extrator diz que ele NAO fecha o
      // fluxo, entao fechar e' responsabilidade daqui. Binario JCR nao fechado
      // e' vazamento de descritor a cada item varrido.
      try (InputStream fluxo = conteudo.getProperty(PROP_DADOS).getStream()) {
        texto.append(extrator.extrair(fluxo, no.getName(), mime));
        return texto.toString();
      } catch (Extrator.ExtracaoIndisponivelException e) {
        LOG.debug("DLP por padrao: {} nao leu o item {} ({}) - tentando o proximo extrator",
                  extrator.getClass().getSimpleName(), entityId, e.getMessage());
      }
    }

    LOG.info("DLP por padrao: nenhum extrator leu o conteudo do item {} - varrido so' por nome/titulo",
             entityId);
    return texto.toString();
  }

  /**
   * Traduz o laudo para o formato que {@code treatItem} consome.
   *
   * <p>O {@code SearchResult} aqui NAO representa uma busca: e' o veiculo que a
   * eXo usa para levar os trechos destacados ate' o registro do item positivo.
   * A ordem dos argumentos do construtor foi lida do bytecode da classe
   * (url, title, excerpt, detail, imageUrl, date, relevancy), e nao suposta.
   *
   * <p>Os trechos vao MASCARADOS. Gravar o CPF em claro no registro da
   * quarentena resolveria a exposicao criando outra: o dado sensivel
   * apareceria na tela de administracao e no banco, fora do documento
   * original. {@link Achado#getAmostrasMascaradas()} entrega so' o suficiente
   * para o administrador reconhecer o que foi encontrado.
   */
  private Collection<SearchResult> sintetizarResultados(ResultadoVarredura resultado) {
    List<SearchResult> resultados = new ArrayList<>();
    Map<String, List<String>> trechos = new HashMap<>();

    List<String> destaques = new ArrayList<>();
    for (Achado achado : resultado.getAchados()) {
      for (String amostra : achado.getAmostrasMascaradas()) {
        destaques.add("<em>" + achado.getRotulo() + "</em> " + amostra);
      }
    }
    trechos.put("dlp-padrao", destaques);

    SearchResult resumo = new SearchResult(null,
                                           resultado.getClassificacao().name(),
                                           resultado.resumo(),
                                           resultado.resumo(),
                                           null,
                                           System.currentTimeMillis(),
                                           0L);
    resumo.setExcerpts(trechos);
    resultados.add(resumo);
    return resultados;
  }

  // ---------------------------------------------------------------------------
  // Leitura de init-params. Configuracao ausente ou invalida SEMPRE cai no
  // padrao declarado -- nunca deixa o conector num estado indefinido.
  // ---------------------------------------------------------------------------

  private static String lerTexto(InitParams parametros, String nome, String padrao) {
    if (parametros == null) {
      return padrao;
    }
    ValueParam parametro = parametros.getValueParam(nome);
    if (parametro == null || parametro.getValue() == null || parametro.getValue().trim().isEmpty()) {
      return padrao;
    }
    return parametro.getValue().trim();
  }

  private static boolean lerBooleano(InitParams parametros, String nome, boolean padrao) {
    String valor = lerTexto(parametros, nome, null);
    return valor == null ? padrao : Boolean.parseBoolean(valor);
  }

  private static long lerInteiro(InitParams parametros, String nome, long padrao) {
    String valor = lerTexto(parametros, nome, null);
    if (valor == null) {
      return padrao;
    }
    try {
      long lido = Long.parseLong(valor);
      return lido > 0 ? lido : padrao;
    } catch (NumberFormatException e) {
      LOG.warn("DLP por padrao: valor invalido em {} ('{}') - usando o padrao {}", nome, valor, padrao);
      return padrao;
    }
  }
}
