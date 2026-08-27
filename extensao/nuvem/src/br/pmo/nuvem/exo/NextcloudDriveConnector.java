package br.pmo.nuvem.exo;

import java.util.Map;

import org.exoplatform.services.cms.clouddrives.CloudDriveConnector;
import org.exoplatform.services.cms.clouddrives.CloudDriveException;
import org.exoplatform.services.cms.clouddrives.CloudProvider;
import org.exoplatform.services.cms.clouddrives.CloudUser;
import org.exoplatform.services.cms.clouddrives.ConfigurationException;
import org.exoplatform.services.cms.clouddrives.jcr.NodeFinder;
import org.exoplatform.services.cms.clouddrives.utils.ExtendedMimeTypeResolver;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.exoplatform.container.xml.InitParams;

import br.pmo.nuvem.CofreTokens;
import br.pmo.nuvem.OAuth2Cliente;
import br.pmo.nuvem.WebDavCliente;

/**
 * Conector Nextcloud/WebDAV para o Cloud Drive da eXo.
 *
 * <p><b>O QUE E'.</b> Implementa os quatro metodos abstratos de
 * {@link CloudDriveConnector} usando o nucleo {@code br.pmo.nuvem}: OAuth2 com
 * refresh no {@link CofreTokens}, WebDAV no {@link WebDavCliente}, e
 * credenciais que nunca tocam em properties nem em log.
 *
 * <p><b>O QUE NAO E'.</b> Nao e' provisionamento: nao sobe servidor Nextcloud,
 * nao cria usuario, nao semea nada. E' um conector que fala com o Nextcloud que
 * JA' EXISTE na rede da prefeitura. Configuracao (URL do servidor, client-id,
 * client-secret) vem do {@code InitParams} -- jamais chumbada no codigo.
 *
 * <p><b>REGRA DE FALHA.</b> A configuracao essencial (server-url, client-id)
 * e' conferida em {@code garantirClientes()}, chamada no {@code authenticate}
 * — no uso real, com o provedor habilitado. O construtor NUNCA valida: o
 * kernel instancia este plugin no boot antes de consultar {@code isDisabled()}
 * (mesmo padrao do GoogleDriveConnector nativo), entao validar no construtor
 * com configuracao vazia quebraria o CloudDriveService com disable=true.
 */
public class NextcloudDriveConnector extends CloudDriveConnector {

  /** Constantes de configuracao especificas do Nextcloud (alem das da base). */
  public static final String CONFIG_SERVER_URL = "server-url";
  public static final String CONFIG_WEBDAV_PATH = "webdav-path";
  public static final String CONFIG_REDIRECT_URI = "redirect-uri";

  private final SessionProviderService sessions;
  private final NodeFinder finder;
  private final ExtendedMimeTypeResolver mime;
  private final CofreTokens cofre;
  private final String serverUrl;
  private final String webdavPath;
  private final String redirect;
  private final String clientId;
  private final String clientSecret;
  private final String schema;
  /** Clientes montados sob demanda em {@link #garantirClientes()}; null enquanto
   *  a configuracao estiver incompleta (estado "nasce desligado"). */
  private volatile OAuth2Cliente oauth;
  private volatile WebDavCliente webdav;

  public NextcloudDriveConnector(RepositoryService jcrService,
                                 SessionProviderService sessionProviders,
                                 NodeFinder jcrFinder,
                                 ExtendedMimeTypeResolver mimeTypes,
                                 InitParams params) throws ConfigurationException {
    super(jcrService, sessionProviders, jcrFinder, mimeTypes, params);
    this.sessions = sessionProviders;
    this.finder = jcrFinder;
    this.mime = mimeTypes;
    this.cofre = new CofreTokens();
    // IMPORTANTE: o construtor NAO valida nem monta os clientes. O kernel
    // instancia este plugin antes de consultar isDisabled(); se o construtor
    // lancasse ConfigurationException com configuracao vazia, o boot do
    // CloudDriveService quebraria mesmo com disable=true. O padrao nativo
    // (GoogleDriveConnector) faz exatamente isto: o construtor so' guarda os
    // valores e a validacao fica para o uso real (garantirClientes), que so'
    // roda com o provedor registrado e habilitado.
    this.serverUrl = param(params, CONFIG_SERVER_URL);
    this.webdavPath = param(params, CONFIG_WEBDAV_PATH);
    this.redirect = param(params, CONFIG_REDIRECT_URI);
    this.clientId = getClientId();
    this.clientSecret = getClientSecret();
    this.schema = getConnectorSchema();
  }

  /** Valida a configuracao e monta os clientes OAuth2/WebDAV. Lanca
   *  {@link CloudDriveException} se faltar o essencial — chamado apenas no uso
   *  real (authenticate), quando o operador ja' habilitou e configurou. */
  private void garantirClientes() throws CloudDriveException {
    if (oauth != null && webdav != null) {
      return;
    }
    synchronized (this) {
      if (oauth != null && webdav != null) {
        return;
      }
      if (serverUrl == null || serverUrl.isEmpty()) {
        throw new CloudDriveException("Nextcloud: '" + CONFIG_SERVER_URL
            + "' nao configurado (exo.nuvem.nextcloud.server-url).");
      }
      if (clientId == null || clientId.isEmpty()) {
        throw new CloudDriveException("Nextcloud: '" + CONFIG_PROVIDER_CLIENT_ID
            + "' nao configurado (exo.nuvem.nextcloud.client-id).");
      }
      // Monta as URLs derivadas: auth e token vivem sob o endpoint OAuth2 do
      // Nextcloud (/apps/oauth2/authorize e /apps/oauth2/api/v1/token).
      String base = (schema == null || schema.isEmpty() ? "https" : schema) + "://" + serverUrl;
      String authUrl = base + "/apps/oauth2/authorize";
      String tokenUrl = base + "/apps/oauth2/api/v1/token";
      this.oauth = new OAuth2Cliente(authUrl, tokenUrl, clientId, clientSecret,
                                     redirect == null ? "" : redirect);

      String dav = (webdavPath == null || webdavPath.isEmpty())
          ? "/remote.php/dav/files/"
          : webdavPath;
      this.webdav = new WebDavCliente(base + dav, new WebDavCliente.TokenSource() {
        @Override
        public String acessar() throws Exception {
          String chave = providerKey();
          if (cofre.expirado(chave, System.currentTimeMillis())) {
            String r = cofre.refresh(chave);
            if (r == null) {
              throw new CloudDriveException("token expirado e sem refresh: reautentique");
            }
            OAuth2Cliente.Tokens novo = oauth.renovar(r);
            cofre.guardar(chave, novo);
          }
          String t = cofre.acesso(chave);
          if (t == null) {
            throw new CloudDriveException("sem token de acesso: reautentique");
          }
          return t;
        }
      });
    }
  }

  @Override
  protected CloudProvider createProvider() {
    // Sempre devolve o provider (nunca lanca): com disable=true o addPlugin
    // nem chega a chamar isto, e com disable=false a UI precisa do provider
    // mesmo antes de o OAuth2 ser exercitado. O redirectURL e' montado como o
    // onedrive nativo (schema://host/portal/rest/clouddrive/connect/nextcloud);
    // sem host configurado, o placeholder literal fica no JSON (mesmo padrao
    // do gdrive nao-configurado) — o item aparece na UI, a falha so' vem ao
    // tentar conectar de verdade.
    String base = (schema == null || schema.isEmpty() ? "https" : schema) + "://"
        + (serverUrl == null || serverUrl.isEmpty() ? "${exo.nuvem.nextcloud.server-url}" : serverUrl);
    String redirect = base + CloudProvider.CONNECT_URL_BASE + getProviderId();
    return new NextcloudProvider(getProviderId(), getProviderName(), redirect);
  }

  @Override
  protected NextcloudUser authenticate(Map<String, String> params) throws CloudDriveException {
    // A configuracao essencial e' conferida aqui, no uso real — nunca no
    // construtor (que roda no boot mesmo com disable=true).
    garantirClientes();
    String code = params.get(CloudDriveConnector.OAUTH2_CODE);
    String state = params.get(CloudDriveConnector.OAUTH2_STATE);
    if (code == null || code.isEmpty()) {
      throw new CloudDriveException("callback OAuth2 sem codigo de autorizacao");
    }
    if (!cofre.conferirState(state, System.currentTimeMillis())) {
      throw new CloudDriveException("state OAuth2 invalido ou expirado: possivel CSRF");
    }
    try {
      OAuth2Cliente.Tokens tokens = oauth.trocarCodigo(code);
      String chave = providerKey();
      cofre.guardar(chave, tokens);
      // O user do Nextcloud nao expoe email; o username vem do proprio
      // parametro de conexao quando o cliente o informa.
      String usuario = params.get("username");
      String email = params.get("email");
      return new NextcloudUser(chave, usuario == null ? currentUser() : usuario,
                               email, getProvider());
    } catch (OAuth2Cliente.TokenExpiradoException e) {
      throw new CloudDriveException("autorizacao recusada pelo servidor: " + e.getMessage(), e);
    } catch (OAuth2Cliente.OAuth2Exception e) {
      throw new CloudDriveException("falha no OAuth2 do Nextcloud: " + e.getMessage(), e);
    }
  }

  @Override
  protected NextcloudDrive createDrive(CloudUser user, javax.jcr.Node node)
      throws CloudDriveException, javax.jcr.RepositoryException {
    garantirClientes();
    return new NextcloudDrive((NextcloudUser) user, node, sessions, finder, mime,
                              cofre, webdav);
  }

  @Override
  protected NextcloudDrive loadDrive(javax.jcr.Node node)
      throws CloudDriveException, javax.jcr.RepositoryException {
    // O node carrega o id do drive; o drive reabre a partir do proprio node.
    garantirClientes();
    return new NextcloudDrive(null, node, sessions, finder, mime, cofre, webdav);
  }

  private String providerKey() {
    return getProviderId() + ":" + currentUser();
  }

  private static String param(InitParams params, String nome) {
    if (params == null) {
      return null;
    }
    try {
      // As propriedades especificas do Nextcloud vivem no MESMO properties-param
      // "drive-configuration" que a classe base le para provider-id/client-id/
      // connector-host — jamais em value-param separado.
      org.exoplatform.container.xml.PropertiesParam p = params.getPropertiesParam("drive-configuration");
      if (p == null) {
        return null;
      }
      String v = p.getProperty(nome);
      return (v == null || v.trim().isEmpty()) ? null : v.trim();
    } catch (Exception e) {
      return null;
    }
  }
}
