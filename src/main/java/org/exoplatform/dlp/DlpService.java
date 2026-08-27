package org.exoplatform.dlp;

import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class DlpService {

  private final DlpEngine dlpEngine = new DlpEngine();
  private final Map<String, DlpViolation> violationLog = new HashMap<>();

  public boolean validateShareContent(String content, String userId, String documentId) {
    if (dlpEngine.containsSensitiveContent(content)) {
      logViolation(userId, documentId, content);
      return false;
    }
    return true;
  }

  public void logViolation(String userId, String documentId, String content) {
    String violationId = UUID.randomUUID().toString();
    DlpViolation violation = new DlpViolation(
      violationId,
      userId,
      documentId,
      LocalDateTime.now(),
      dlpEngine.detectSensitivePatterns(content),
      "BLOCKED"
    );
    violationLog.put(violationId, violation);
  }

  public List<DlpViolation> getViolationLog(int limit) {
    return new ArrayList<>(violationLog.values())
      .stream()
      .limit(limit)
      .toList();
  }

  public static class DlpViolation {
    public String id;
    public String userId;
    public String documentId;
    public LocalDateTime timestamp;
    public List<String> detectedPatterns;
    public String status;

    public DlpViolation(String id, String userId, String documentId,
                       LocalDateTime timestamp, List<String> patterns, String status) {
      this.id = id;
      this.userId = userId;
      this.documentId = documentId;
      this.timestamp = timestamp;
      this.detectedPatterns = patterns;
      this.status = status;
    }
  }
}
