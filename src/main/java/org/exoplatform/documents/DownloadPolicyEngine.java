package org.exoplatform.documents;

import java.util.*;

/**
 * Engine para restrições avançadas de download
 * Exemplo: "PDFs só podem ser baixados por Admin"
 * "Arquivos > 100MB só por gerentes"
 */
public class DownloadPolicyEngine {

  private final List<DownloadPolicy> policies = new ArrayList<>();

  public DownloadPolicyEngine() {
    // Politicas padrão
    policies.add(new DownloadPolicy("pdf-admin-only", "pdf", 0, Arrays.asList("ADMIN")));
    policies.add(new DownloadPolicy("large-file-manager", "exe", 100, Arrays.asList("MANAGER", "ADMIN")));
  }

  public boolean canDownload(Document doc, User user) {
    for (DownloadPolicy policy : policies) {
      if (matchesPolicy(doc, policy)) {
        // Verifica se usuário tem role permitida
        if (!hasRequiredRole(user, policy.allowedRoles)) {
          return false; // Bloqueado
        }
      }
    }
    return true; // Permitido
  }

  private boolean matchesPolicy(Document doc, DownloadPolicy policy) {
    // Verifica extension
    if (!policy.fileType.equals("*")) {
      if (!doc.getFileName().endsWith("." + policy.fileType)) {
        return false;
      }
    }

    // Verifica tamanho mínimo
    if (policy.minSizeMB > 0) {
      if (doc.getSize() < policy.minSizeMB * 1024 * 1024) {
        return false;
      }
    }

    return true;
  }

  private boolean hasRequiredRole(User user, List<String> requiredRoles) {
    for (String role : user.getRoles()) {
      if (requiredRoles.contains(role)) {
        return true;
      }
    }
    return false;
  }

  public void addPolicy(DownloadPolicy policy) {
    policies.add(policy);
  }

  public static class DownloadPolicy {
    public String id;
    public String fileType; // "pdf", "exe", "*" = all
    public long minSizeMB; // 0 = qualquer tamanho
    public List<String> allowedRoles;

    public DownloadPolicy(String id, String fileType, long minSizeMB, List<String> allowedRoles) {
      this.id = id;
      this.fileType = fileType;
      this.minSizeMB = minSizeMB;
      this.allowedRoles = allowedRoles;
    }
  }

  public static class Document {
    private String fileName;
    private long size;

    public Document(String fileName, long size) {
      this.fileName = fileName;
      this.size = size;
    }

    public String getFileName() { return fileName; }
    public long getSize() { return size; }
  }

  public static class User {
    private String id;
    private List<String> roles;

    public User(String id, List<String> roles) {
      this.id = id;
      this.roles = roles;
    }

    public List<String> getRoles() { return roles; }
  }
}
