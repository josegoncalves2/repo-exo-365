package br.pmo.nuvem.exo;

import org.exoplatform.services.cms.clouddrives.CloudDriveException;
import org.exoplatform.services.cms.clouddrives.CloudProvider;

/**
 * Provedor Nextcloud para o Cloud Drive.
 *
 * <p>Nao usa OAuth2 para montar a URL de autorizacao DIRETAMENTE aqui: a URL de
 * autorizacao vive no {@link br.pmo.nuvem.OAuth2Cliente}, que o conector monta
 * a partir da configuracao. Este provedor existe para satisfazer o contrato da
 * plataforma (o {@code CloudDriveConnector} exige um {@code CloudProvider}) e
 * para expor o {@code redirectURL} que a UI usa para iniciar o fluxo.
 */
public class NextcloudProvider extends CloudProvider {

  private final String redirectURL;

  public NextcloudProvider(String id, String name) {
    this(id, name, null);
  }

  public NextcloudProvider(String id, String name, String redirectURL) {
    super(id, name);
    this.redirectURL = redirectURL;
  }

  @Override
  public String getAuthURL() throws CloudDriveException {
    // A URL real de autorizacao (com client_id, state etc.) e' montada pelo
    // OAuth2Cliente do nucleo; aqui devolvemos o alvo base para quem quiser
    // apenas verificar conectividade do endpoint.
    if (redirectURL == null) {
      throw new CloudDriveException("provedor sem URL de autorizacao configurada");
    }
    return redirectURL;
  }

  public String getRedirectURL() {
    return redirectURL;
  }

  @Override
  public boolean retryOnProviderError() {
    // O nucleo ja' trata refresh de token e 401; nao ha' motivo para a
    // plataforma reintentar operacoes em erro de provedor.
    return false;
  }
}
