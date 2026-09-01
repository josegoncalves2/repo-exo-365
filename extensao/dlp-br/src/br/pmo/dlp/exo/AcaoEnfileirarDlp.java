package br.pmo.dlp.exo;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.jcr.Item;

import org.apache.commons.chain.Context;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.dlp.connector.DlpServiceConnector;
import org.exoplatform.dlp.processor.DlpOperationProcessor;
import org.exoplatform.dlp.queue.QueueDlpService;
import org.exoplatform.services.cms.documents.TrashService;
import org.exoplatform.services.ext.action.InvocationContext;
import org.exoplatform.services.jcr.impl.core.NodeImpl;
import org.exoplatform.services.jcr.impl.core.PropertyImpl;
import org.exoplatform.services.jcr.impl.ext.action.AdvancedAction;
import org.exoplatform.services.jcr.impl.ext.action.AdvancedActionException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Enfileira arquivos para a varredura DLP por PADRAO.
 *
 * <h2>Por que esta acao precisa existir</h2>
 *
 * A acao oficial do add-on, {@code FileDLPAction}, tem um portao no comeco:
 *
 * <pre>
 *   if (StringUtils.isBlank(dlpOperationProcessor.getKeywords())) return;
 * </pre>
 *
 * Ou seja: <b>sem palavra-chave cadastrada, NADA e' enfileirado</b> e nenhum
 * conector roda. Isso faz sentido para deteccao por palavra-chave (lista vazia
 * = nada a procurar), mas inviabiliza a deteccao por PADRAO: um CPF e' um CPF
 * independentemente de alguem ter digitado alguma palavra numa tela. Amarrar a
 * varredura de padroes a uma lista de palavras seria entregar um DLP que so'
 * funciona se o administrador adivinhar o que procurar.
 *
 * Esta acao e' ACRESCENTADA ao catalogo, com {@code <name>} proprio. A oficial
 * continua registrada e continua funcionando: as duas convivem, e um mesmo
 * arquivo enfileirado duas vezes e' idempotente (o processador trata a fila
 * por entidade).
 *
 * <h2>AS DUAS TRAVAS, e por que sao inegociaveis</h2>
 *
 * Enfileirar sem pensar aqui pode destruir o acervo. O raciocinio:
 *
 * <ol>
 *   <li>{@code FileDlpConnector#processItem} NAO confere se ha' palavras-chave
 *       ; quem conferia era a acao oficial, do lado de fora. Ele vai direto a'
 *       busca no Elasticsearch usando a lista configurada.</li>
 *   <li>Com {@code exo.dlp.keywords} VAZIO (que e' o estado desta instalacao),
 *       essa busca e' feita com termo vazio. Busca vazia tende a casar com
 *       TUDO.</li>
 *   <li>Casar com tudo significa {@code treatItem} em tudo, ou seja
 *       <b>o acervo inteiro para a quarentena</b>.</li>
 * </ol>
 *
 * Por isso:
 *
 * <ul>
 *   <li><b>Trava 1 ; so' enfileira se o conector de padrao ESTIVER no lugar.</b>
 *       Confere-se o objeto real registrado no mapa do processador sob o tipo
 *       "file". Se, por ordem de carga, quem ficou registrado foi o conector
 *       nativo, esta acao nao faz nada e o portal se comporta exatamente como
 *       antes desta extensao. Nao se confia na prioridade declarada: mede-se.</li>
 *   <li><b>Trava 2 ; nao enfileira o que ja' esta' na lixeira</b>, mesmo
 *       criterio da acao oficial.</li>
 * </ul>
 *
 * A trava complementar ; nao delegar para a busca por palavra-chave quando a
 * lista esta' vazia ; vive em {@link ConectorDlpRegex#processItem(String)}.
 * Sao duas defesas independentes para o mesmo desastre, de proposito.
 */
public class AcaoEnfileirarDlp implements AdvancedAction {

  private static final Log LOG = ExoLogger.getExoLogger(AcaoEnfileirarDlp.class);

  /** Tipo com que o processador despacha para o conector de arquivos. */
  private static final String TIPO_ARQUIVO = "file";

  private static final String NT_FILE = "nt:file";
  private static final String NT_RESOURCE = "nt:resource";

  /** Mesma exclusao da acao oficial: propriedades de controle do editor e da
   *  restauracao mudam sozinhas e reenfileirariam o arquivo em laco. */
  private static final List<String> PROPRIEDADES_IGNORADAS =
      Collections.unmodifiableList(Arrays.asList("exo:editorsId",
                                                 "exo:currentProvider",
                                                 "exo:restorePath"));

  @Override
  public boolean execute(Context contexto) throws Exception {
    try {
      DlpOperationProcessor processador = CommonsUtils.getService(DlpOperationProcessor.class);
      if (processador == null) {
        return true;
      }

      // TRAVA 1: o conector de padrao esta' mesmo registrado?
      DlpServiceConnector registrado = processador.getConnectors().get(TIPO_ARQUIVO);
      if (!(registrado instanceof ConectorDlpRegex)) {
        // O conector nativo venceu o registro. Enfileirar aqui entregaria os
        // itens a ele, que faria busca por palavra-chave vazia. Silencio e' a
        // resposta correta.
        return true;
      }

      ExoFeatureService recursos = CommonsUtils.getService(ExoFeatureService.class);
      if (recursos != null && !recursos.isActiveFeature(DlpOperationProcessor.DLP_FEATURE)) {
        return true;
      }

      Object item = contexto.get(InvocationContext.CURRENT_ITEM);
      NodeImpl no;

      if (item instanceof PropertyImpl) {
        PropertyImpl propriedade = (PropertyImpl) item;
        if (PROPRIEDADES_IGNORADAS.contains(propriedade.getName())) {
          return true;
        }
        no = (NodeImpl) propriedade.getParent();
      } else if (item instanceof NodeImpl) {
        no = (NodeImpl) item;
      } else {
        return true;
      }

      // nt:resource e' o no' de conteudo; quem interessa e' o nt:file pai.
      if (no.isNodeType(NT_RESOURCE)) {
        Item pai = no.getParent();
        if (!(pai instanceof NodeImpl)) {
          return true;
        }
        no = (NodeImpl) pai;
      }

      if (!no.isNodeType(NT_FILE)) {
        return true;
      }

      // TRAVA 2: lixeira nao se varre.
      TrashService lixeira = CommonsUtils.getService(TrashService.class);
      if (lixeira != null && lixeira.isInTrash(no)) {
        return true;
      }

      QueueDlpService fila = CommonsUtils.getService(QueueDlpService.class);
      if (fila == null) {
        return true;
      }

      String idEntidade = no.getInternalIdentifier();
      // ORDEM DOS ARGUMENTOS -- CORRIGIDA 2026-08-31. Era
      //   fila.addToQueue(idEntidade, TIPO_ARQUIVO)
      // e estava invertida. A assinatura e' addToQueue(entityType, entityId):
      // QueueDlpServiceImpl.getDlpOperation(a, b) faz setEntityType(a) e
      // setEntityId(b) (lido no bytecode), e o FileDLPAction nativo chama
      // addToQueue("file", uuid).
      //
      // O estrago nao era so' "este item nao e' varrido". Cada gravacao
      // inseria em DLP_QUEUE uma linha com ENTITY_TYPE = <uuid do no>, e
      // DlpOperationProcessorImpl.processBulk agrupa a fila por ENTITY_TYPE e
      // faz getConnectors().get(tipo). Para um uuid nao ha conector, entao:
      //   NullPointerException: Cannot invoke
      //     "DlpServiceConnector.processItem(String)" because "connector" is null
      // A excecao aborta o BULK INTEIRO, e junto com ele as linhas corretas
      // que o FileDLPAction havia enfileirado. Resultado medido: DLP_QUEUE
      // com 10 itens parados, DLP_POSITIVE_ITEMS vazia e nenhum documento
      // varrido -- uma linha invertida desligava o DLP inteiro em silencio.
      fila.addToQueue(TIPO_ARQUIVO, idEntidade);
      LOG.debug("DLP por padrao: item {} enfileirado para varredura", idEntidade);

    } catch (Exception e) {
      // Uma acao JCR que lanca aborta a GRAVACAO do usuario. Nenhuma falha de
      // enfileiramento vale impedir alguem de salvar um documento: registra-se
      // e segue. O arquivo simplesmente nao e' varrido agora.
      LOG.error("DLP por padrao: falha ao enfileirar item para varredura", e);
    }
    return true;
  }

  @Override
  public void onError(Exception erro, Context contexto) throws AdvancedActionException {
    LOG.error("DLP por padrao: erro na acao de enfileiramento", erro);
  }
}
