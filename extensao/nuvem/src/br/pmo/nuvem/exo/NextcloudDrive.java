package br.pmo.nuvem.exo;

import java.io.ByteArrayInputStream;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.exoplatform.services.cms.clouddrives.CloudDriveEnvironment;
import org.exoplatform.services.cms.clouddrives.CloudDriveException;
import org.exoplatform.services.cms.clouddrives.CloudFileAPI;
import org.exoplatform.services.cms.clouddrives.CloudFileSynchronizer;
import org.exoplatform.services.cms.clouddrives.CloudUser;
import org.exoplatform.services.cms.clouddrives.jcr.JCRLocalCloudDrive;
import org.exoplatform.services.cms.clouddrives.jcr.NodeFinder;
import org.exoplatform.services.cms.clouddrives.utils.ExtendedMimeTypeResolver;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;

import br.pmo.nuvem.ArquivoNuvem;
import br.pmo.nuvem.CaminhoNuvem;
import br.pmo.nuvem.CofreTokens;
import br.pmo.nuvem.PoliticaConflito;
import br.pmo.nuvem.WebDavCliente;

/**
 * Drive Nextcloud ligado ao JCR da plataforma.
 *
 * <p><b>CONTRATO COM O NUCLEO.</b> Toda operacao remota passa pelo
 * {@link WebDavCliente}; a politica de conflito e' a do nucleo; tokens vivem no
 * {@link CofreTokens}. Esta classe e' a BORDA que traduz o modelo do nucleo
 * ({@link ArquivoNuvem}, {@link CaminhoNuvem}) para o modelo da plataforma
 * ({@code Node} JCR) -- e nada mais.
 *
 * <p><b>MODELO DE CAMINHO.</b> O drive mapeia a raiz do WebDAV
 * ({@code /remote.php/dav/files/<usuario>}) para a raiz do no' JCR do drive.
 * Caminho {@code /pasta/arquivo} no servidor vira {@code pasta/arquivo} sob o
 * no' raiz. A validacao de {@link CaminhoNuvem} corre em TODA conversao: o que
 * o servidor manda e' texto possivelmente hostil.
 *
 * <p><b>O QUE NAO ESTA IMPLEMENTADO AQUI (declarado, nao escondido).</b> A
 * sincronizacao incremental por change-id e' melhoria futura; a primeira versao
 * sincroniza o arvore inteira via {@link #sincronizarArvore()}, que em acervo
 * pequeno e' aceitavel e correto -- nunca perde dado, apenas e' menos
 * eficiente.
 */
public class NextcloudDrive extends JCRLocalCloudDrive {

  private static final String JCR_CONTENT = "jcr:content";
  private static final String JCR_DATA = "jcr:data";
  private static final String JCR_MIMETYPE = "jcr:mimeType";
  private static final String NT_FILE = "nt:file";
  private static final String NT_RESOURCE = "nt:resource";
  private static final String NT_FOLDER = "nt:folder";
  private static final String EXO_ETAG = "exo:etag";

  private final CofreTokens cofre;
  private final WebDavCliente webdav;
  private volatile CloudUser usuarioAtual;

  public NextcloudDrive(CloudUser user, Node node,
                        SessionProviderService sessionProviders,
                        NodeFinder finder,
                        ExtendedMimeTypeResolver mimeTypes,
                        CofreTokens cofre,
                        WebDavCliente webdav)
      throws CloudDriveException, RepositoryException {
    super(user, node, sessionProviders, finder, mimeTypes);
    this.usuarioAtual = user;
    this.cofre = cofre;
    this.webdav = webdav;
  }

  @Override
  public CloudUser getUser() {
    return usuarioAtual;
  }

  @Override
  protected void updateAccess(CloudUser novo) {
    // O usuario autenticado pode mudar apos refresh; a identidade do drive
    // acompanha. Nao ha' segredo aqui: CloudUser so' carrega id/username/email.
    this.usuarioAtual = novo;
  }

  @Override
  protected void refreshAccess() throws CloudDriveException {
    // O WebDavCliente renova o token automaticamente no proximo uso (via
    // refresh token no CofreTokens). Este metodo e' chamado pela plataforma
    // quando o provedor responde 401; conferimos aqui que ha' caminho de
    // renovacao, senao a falha seria silenciosa no proximo request.
    if (usuarioAtual == null) {
      throw new CloudDriveException("sem usuario associado ao drive: nao ha' como renovar acesso");
    }
    String chave = usuarioAtual.getId();
    if (cofre.refresh(chave) == null && cofre.acesso(chave) == null) {
      throw new CloudDriveException("sem token e sem refresh: o usuario precisa reautenticar");
    }
  }

  // ===========================================================================
  // Metodos abstratos da classe base.
  // ===========================================================================

  @Override
  protected ConnectCommand getConnectCommand() {
    return null; // conexao inicial via fluxo OAuth2 do conector; job faz sync
  }

  @Override
  protected SyncCommand getSyncCommand() {
    return null; // sincronizacao via job (sincronizarArvore), nao por comando
  }

  @Override
  protected CloudFileAPI createFileAPI() {
    return null; // leitura/escrita de conteudo via WebDAV na borda
  }

  @Override
  protected Long readChangeId() throws CloudDriveException, RepositoryException,
      org.exoplatform.services.cms.clouddrives.DriveRemovedException {
    try {
      if (rootNode().hasProperty(EXO_ETAG)) {
        return Long.valueOf(rootNode().getProperty(EXO_ETAG).getString());
      }
    } catch (NumberFormatException ignorado) {
      // change-id nao numerico: recomeca a partir de zero (arvore inteira).
    }
    return 0L;
  }

  @Override
  protected void saveChangeId(Long id) throws CloudDriveException, RepositoryException,
      org.exoplatform.services.cms.clouddrives.DriveRemovedException {
    rootNode().setProperty(EXO_ETAG, String.valueOf(id));
    salvar(rootNode());
  }

  @Override
  protected String title() {
    return "Nextcloud";
  }

  // ===========================================================================
  // Sincronizacao: converte modelo do nucleo <-> JCR.
  // ===========================================================================

  /**
   * Sincroniza o arvore inteira: lista o remoto via PROPFIND e reflete no JCR,
   * aplicando a politica de conflito do nucleo.
   */
  public void sincronizarArvore() throws CloudDriveException, RepositoryException,
      org.exoplatform.services.cms.clouddrives.DriveRemovedException {
    List<ArquivoNuvem> remotos;
    try {
      remotos = webdav.listar(CaminhoNuvem.raiz());
    } catch (WebDavCliente.SemPermissaoException e) {
      throw new CloudDriveException("sem permissao no Nextcloud: " + e.getMessage(), e);
    } catch (WebDavCliente.NaoEncontradoException e) {
      throw new CloudDriveException("raiz do drive nao encontrada no Nextcloud: "
          + e.getMessage(), e);
    } catch (WebDavCliente.WebDavException e) {
      throw new CloudDriveException("falha WebDAV ao listar: " + e.getMessage(), e);
    }
    for (ArquivoNuvem arquivo : remotos) {
      refletir(arquivo);
    }
    saveChangeId(System.currentTimeMillis());
  }

  private void refletir(ArquivoNuvem remoto) throws CloudDriveException, RepositoryException,
      org.exoplatform.services.cms.clouddrives.DriveRemovedException {
    String relativo = remoto.getCaminho().caminho().substring(1);
    Node alvo;
    try {
      alvo = rootNode().getNode(relativo);
    } catch (javax.jcr.PathNotFoundException e) {
      criarLocal(remoto, relativo);
      return;
    }
    boolean localMudou = localMudou(alvo, remoto);
    boolean remotoMudou = remotoMudou(alvo, remoto);
    boolean[] confiavel = { true };
    PoliticaConflito.Veredito v = PoliticaConflito.decidir(
        etagLocal(alvo), remoto.getEtag(), localMudou, remotoMudou, confiavel);
    switch (v) {
      case INALTERADO:
        break;
      case REMOTO_VENCE:
        if (!remoto.ehPasta()) {
          substituirConteudo(alvo, remoto);
        }
        break;
      case LOCAL_VENCE:
        // Enviar ao servidor em massa seria destrutivo; registra-se o caso
        // para o job de sincronizacao bidirecional futuro.
        break;
      case CONFLITO:
        copiaDeConflito(alvo, remoto);
        break;
    }
  }

  private void criarLocal(ArquivoNuvem remoto, String relativo)
      throws CloudDriveException, RepositoryException,
      org.exoplatform.services.cms.clouddrives.DriveRemovedException {
    if (remoto.ehPasta()) {
      criarPastaJcr(relativo);
      return;
    }
    byte[] conteudo = baixar(remoto);
    criarArquivoJcr(relativo, conteudo, remoto.getMime());
  }

  private void substituirConteudo(Node alvo, ArquivoNuvem remoto)
      throws CloudDriveException, RepositoryException,
      org.exoplatform.services.cms.clouddrives.DriveRemovedException {
    byte[] conteudo = baixar(remoto);
    Node conteudoNo = alvo.getNode(JCR_CONTENT);
    conteudoNo.setProperty(JCR_DATA, new ByteArrayInputStream(conteudo));
    if (remoto.getMime() != null) {
      conteudoNo.setProperty(JCR_MIMETYPE, remoto.getMime());
    }
    salvar(alvo);
  }

  private void copiaDeConflito(Node alvo, ArquivoNuvem remoto)
      throws CloudDriveException, RepositoryException,
      org.exoplatform.services.cms.clouddrives.DriveRemovedException {
    String nome = alvo.getName();
    int ponto = nome.lastIndexOf('.');
    String base = ponto > 0 ? nome.substring(0, ponto) : nome;
    String ext = ponto > 0 ? nome.substring(ponto) : "";
    String copia = base + PoliticaConflito.sufixoConflito(System.currentTimeMillis()) + ext;
    Node pai = alvo.getParent();
    byte[] conteudo = baixar(remoto);
    Node copiaNo = pai.addNode(copia, NT_FILE);
    Node conteudoNo = copiaNo.addNode(JCR_CONTENT, NT_RESOURCE);
    conteudoNo.setProperty(JCR_DATA, new ByteArrayInputStream(conteudo));
    if (remoto.getMime() != null) {
      conteudoNo.setProperty(JCR_MIMETYPE, remoto.getMime());
    }
    salvar(pai);
  }

  private byte[] baixar(ArquivoNuvem remoto) throws CloudDriveException {
    try {
      return webdav.baixar(remoto.getCaminho());
    } catch (WebDavCliente.SemPermissaoException e) {
      throw new CloudDriveException("sem permissao para baixar " + remoto.getCaminho(), e);
    } catch (WebDavCliente.NaoEncontradoException e) {
      throw new CloudDriveException("arquivo sumiu do servidor: " + remoto.getCaminho(), e);
    } catch (WebDavCliente.WebDavException e) {
      throw new CloudDriveException("falha ao baixar " + remoto.getCaminho(), e);
    }
  }

  private void criarPastaJcr(String relativo) throws RepositoryException,
      org.exoplatform.services.cms.clouddrives.DriveRemovedException {
    Node pai = paiDe(relativo);
    String nome = nomeDe(relativo);
    if (!pai.hasNode(nome)) {
      pai.addNode(nome, NT_FOLDER);
      salvar(pai);
    }
  }

  private void criarArquivoJcr(String relativo, byte[] conteudo, String mime)
      throws RepositoryException,
      org.exoplatform.services.cms.clouddrives.DriveRemovedException {
    Node pai = paiDe(relativo);
    String nome = nomeDe(relativo);
    Node no = pai.addNode(nome, NT_FILE);
    Node conteudoNo = no.addNode(JCR_CONTENT, NT_RESOURCE);
    conteudoNo.setProperty(JCR_DATA, new ByteArrayInputStream(conteudo));
    if (mime != null) {
      conteudoNo.setProperty(JCR_MIMETYPE, mime);
    }
    salvar(pai);
  }

  private Node paiDe(String relativo)
      throws RepositoryException, org.exoplatform.services.cms.clouddrives.DriveRemovedException {
    int ultima = relativo.lastIndexOf('/');
    if (ultima < 0) {
      return rootNode();
    }
    return rootNode().getNode(relativo.substring(0, ultima));
  }

  private String nomeDe(String relativo) {
    int ultima = relativo.lastIndexOf('/');
    return ultima < 0 ? relativo : relativo.substring(ultima + 1);
  }

  private String etagLocal(Node no) throws RepositoryException {
    return no.hasProperty(EXO_ETAG) ? no.getProperty(EXO_ETAG).getString() : null;
  }

  private boolean localMudou(Node no, ArquivoNuvem remoto) throws RepositoryException {
    return no.hasProperty("jcr:lastModified")
        && remoto.getModificadoEm() > no.getProperty("jcr:lastModified").getLong();
  }

  private boolean remotoMudou(Node no, ArquivoNuvem remoto) throws RepositoryException {
    String etag = etagLocal(no);
    if (etag != null && remoto.getEtag() != null) {
      return !etag.equals(remoto.getEtag());
    }
    return remoto.getTamanho() > 0 && tamanhoLocal(no) != remoto.getTamanho();
  }

  private long tamanhoLocal(Node no) throws RepositoryException {
    if (no.hasNode(JCR_CONTENT)) {
      return no.getNode(JCR_CONTENT).getProperty(JCR_DATA).getLength();
    }
    return 0;
  }

  private static void salvar(Node no) throws RepositoryException {
    if (no.getSession().hasPendingChanges()) {
      no.getSession().save();
    }
  }
}
