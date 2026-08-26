package org.exoplatform.dlp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/portal/rest/v1/dlp")
public class DlpController {

  @Autowired
  private DlpService dlpService;

  @PostMapping("/validate")
  public ResponseEntity<?> validateContent(@RequestParam String content,
                                           @RequestParam String userId,
                                           @RequestParam String documentId) {
    boolean isValid = dlpService.validateShareContent(content, userId, documentId);
    if (!isValid) {
      return ResponseEntity.status(403).body(new ErrorResponse(
        "DLP_VIOLATION",
        "Documento contém dados sensíveis. Compartilhamento bloqueado."
      ));
    }
    return ResponseEntity.ok(new SuccessResponse("Compartilhamento permitido"));
  }

  @GetMapping("/violations")
  public ResponseEntity<?> getViolationLog(@RequestParam(defaultValue = "100") int limit) {
    List<DlpService.DlpViolation> violations = dlpService.getViolationLog(limit);
    return ResponseEntity.ok(violations);
  }

  public static class ErrorResponse {
    public String code;
    public String message;

    public ErrorResponse(String code, String message) {
      this.code = code;
      this.message = message;
    }
  }

  public static class SuccessResponse {
    public String message;
    public SuccessResponse(String message) { this.message = message; }
  }
}
