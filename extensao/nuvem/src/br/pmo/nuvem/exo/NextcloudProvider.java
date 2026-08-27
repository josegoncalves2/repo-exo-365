package br.pmo.nuvem.exo;

import org.exoplatform.services.cms.clouddrives.CloudDriveException;
import org.exoplatform.services.cms.clouddrives.CloudProvider;

/**
 * Provedor Nextcloud para o Cloud Drive.
 *
 * <p>Espelha o {@code GoogleProvider} nativo em um ponto critico:
 * {@link #getAuthURL()} NUNCA lanca. O serializador JSON da REST
 * ({@code ProviderService.getById}) chama {@code getAuthURL()} para montar o
 * JSON de cada provider; se lancar, o provider registrado aparece como 200 com
 * corpo vazio e a UI nao renderiza o botao. Sem configuracao, a URL e'
 * montada com o que existe (ate' o placeholder literal do host), exatamente
 * como o gdrive nao-configurado expoe {@code client_id=${clouddrive.google.
 * client.id}} no proprio JSON.
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
    // NUNCA lanca: a serializacao JSON chama isto. Devolve a URL base de
    // autorizacao (o alvo real com client_id/state e' montado pelo
    // OAuth2Cliente no fluxo de connect), ou o redirect da plataforma.
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
