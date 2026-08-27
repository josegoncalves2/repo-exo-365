package br.pmo.nuvem.exo;

import org.exoplatform.services.cms.clouddrives.CloudDriveException;
import org.exoplatform.services.cms.clouddrives.CloudProvider;
import org.exoplatform.services.cms.clouddrives.CloudUser;

/**
 * Usuario do Nextcloud: quem autorizou o drive.
 *
 * <p>O Nextcloud nao expoe e-mail no fluxo OAuth2 padrao; {@code email} pode
 * ser nulo, e {@link #toString()} nunca revela credencial -- so' o id e o
 * provedor.
 */
public class NextcloudUser extends CloudUser {

  public NextcloudUser(String id, String username, String email, CloudProvider provider) {
    super(id, username, email, provider);
  }

  @Override
  public String createDriveTitle() throws CloudDriveException, javax.jcr.RepositoryException {
    String nome = getUsername();
    if (nome == null || nome.isEmpty()) {
      nome = getProvider().getName();
    }
    return "Nextcloud (" + nome + ")";
  }

  @Override
  public String toString() {
    return "NextcloudUser[id=" + getId() + ", provider=" + getProvider().getName() + "]";
  }
}
