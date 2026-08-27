package br.pmo.nuvem.exo;

import java.util.Collections;
import java.util.LinkedHashMap;
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
 * <p><b>REGRA DE FALHA.</b> {@code createProvider()} com configuracao
 * incompleta lanca {@link ConfigurationException} -- conector registrado com
 * configuracao faltando e' conector que "funciona" mas nao conecta, exatamente
 * o tipo de feature pela metade que o operador recusou.
 */
public class NextcloudDriveConnector extends CloudDriveConnector {

  /** Constantes de configuracao especificas do Nextcloud (alem das da base). */
  public static final String CONFIG_SERVER_URL = "server-url";
  public static final String CONFIG_WEBDAV_PATH = "webdav-path";
  public static final String CONFIG_REDIRECT_URI = "redirect-uri";

  private final RepositoryService jcr;
  private final SessionProviderService sessions;
  private final NodeFinder finder;
  private final ExtendedMimeTypeResolver mime;
  private final CofreTokens cofre;
  private final OAuth2Cliente oauth;
  private final WebDavCliente webdav;
  private final InitParams paramsIniciais;

  public NextcloudDriveConnector(RepositoryService jcrService,
                                 SessionProviderService sessionProviders,
                                 NodeFinder jcrFinder,
                                 ExtendedMimeTypeResolver mimeTypes,
                                 InitParams params) throws ConfigurationException {
    super(jcrService, sessionProviders, jcrFinder, mimeTypes, params);
    this.jcr = jcrService;
    this.sessions = sessionProviders;
    this.finder = jcrFinder;
    this.mime = mimeTypes;
    this.cofre = new CofreTokens();
    this.paramsIniciais = params;

    String serverUrl = param(params, CONFIG_SERVER_URL);
    String webdavPath = param(params, CONFIG_WEBDAV_PATH);
    String redirect = param(params, CONFIG_REDIRECT_URI);
    String clientId = getClientId();
    String clientSecret = getClientSecret();
    String schema = getConnectorSchema();

    if (serverUrl == null || serverUrl.isEmpty()) {
      throw new ConfigurationException("Nextcloud: '" + CONFIG_SERVER_URL
          + "' e' obrigatorio; sem ele o conector nao conecta em lugar nenhum.");
    }
    if (clientId == null || clientId.isEmpty()) {
      throw new ConfigurationException("Nextcloud: '" + CONFIG_PROVIDER_CLIENT_ID
          + "' e' obrigatorio; sem ele o OAuth2 nao autoriza.");
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

  @Override
  protected CloudProvider createProvider() {
    return new NextcloudProvider(getProviderId(), getProviderName());
  }

  @Override
  protected NextcloudUser authenticate(Map<String, String> params) throws CloudDriveException {
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
    return new NextcloudDrive((NextcloudUser) user, node, sessions, finder, mime,
                              cofre, webdav);
  }

  @Override
  protected NextcloudDrive loadDrive(javax.jcr.Node node)
      throws CloudDriveException, javax.jcr.RepositoryException {
    // O node carrega o id do drive; o drive reabre a partir do proprio node.
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
      org.exoplatform.container.xml.ValueParam v = params.getValueParam(nome);
      return v == null ? null : v.getValue();
    } catch (Exception e) {
      return null;
    }
  }

  private Map<String, String> configMap() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put(CONFIG_SERVER_URL, param(paramsIniciais, CONFIG_SERVER_URL));
    m.put(CONFIG_WEBDAV_PATH, param(paramsIniciais, CONFIG_WEBDAV_PATH));
    m.put(CONFIG_REDIRECT_URI, param(paramsIniciais, CONFIG_REDIRECT_URI));
    return Collections.unmodifiableMap(m);
  }
}
