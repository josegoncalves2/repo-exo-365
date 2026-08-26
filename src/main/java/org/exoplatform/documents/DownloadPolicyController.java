package org.exoplatform.documents;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/portal/rest/v1/documents")
public class DownloadPolicyController {

  private final DownloadPolicyEngine policyEngine = new DownloadPolicyEngine();

  @PostMapping("/can-download")
  public ResponseEntity<?> canDownloadFile(@RequestParam String fileName,
                                          @RequestParam long sizeMB,
                                          @RequestParam List<String> userRoles) {
    DownloadPolicyEngine.Document doc = new DownloadPolicyEngine.Document(fileName, sizeMB * 1024 * 1024);
    DownloadPolicyEngine.User user = new DownloadPolicyEngine.User("current-user", userRoles);

    boolean allowed = policyEngine.canDownload(doc, user);

    return ResponseEntity.ok(new DownloadCheckResponse(
      allowed,
      allowed ? "Download permitido" : "Download bloqueado por política"
    ));
  }

  @PostMapping("/policies")
  public ResponseEntity<?> addPolicy(@RequestBody PolicyRequest req) {
    DownloadPolicyEngine.DownloadPolicy policy = new DownloadPolicyEngine.DownloadPolicy(
      UUID.randomUUID().toString(),
      req.fileType,
      req.minSizeMB,
      req.allowedRoles
    );
    policyEngine.addPolicy(policy);
    return ResponseEntity.ok(new ActionResponse("SUCCESS", "Política criada"));
  }

  public static class DownloadCheckResponse {
    public boolean allowed;
    public String message;

    public DownloadCheckResponse(boolean allowed, String message) {
      this.allowed = allowed;
      this.message = message;
    }
  }

  public static class PolicyRequest {
    public String fileType;
    public long minSizeMB;
    public List<String> allowedRoles;
  }

  public static class ActionResponse {
    public String status;
    public String message;

    public ActionResponse(String status, String message) {
      this.status = status;
      this.message = message;
    }
  }
}
