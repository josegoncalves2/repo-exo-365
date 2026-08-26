package org.exoplatform.addons.dlp;

import org.exoplatform.services.security.Identity;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Data Leak Protection Service
 * Monitora e previne compartilhamento indevido de dados sensíveis
 */
@Service
public class DLPService {

    private static final Pattern CREDIT_CARD = Pattern.compile("\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}");
    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern CPF = Pattern.compile("\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}");
    private static final Pattern SSN = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");

    private final Map<String, DLPPolicy> policies = new HashMap<>();

    public DLPService() {
        initializeDefaultPolicies();
    }

    private void initializeDefaultPolicies() {
        policies.put("CREDIT_CARD", new DLPPolicy("CREDIT_CARD", "Credit Card Detection", CREDIT_CARD, true));
        policies.put("CPF", new DLPPolicy("CPF", "Brazilian CPF Detection", CPF, true));
        policies.put("SSN", new DLPPolicy("SSN", "Social Security Number Detection", SSN, true));
    }

    /**
     * Analisa conteúdo para dados sensíveis
     */
    public DLPAnalysisResult analyzeContent(String content, Identity user) {
        DLPAnalysisResult result = new DLPAnalysisResult();
        result.setUser(user.getUserId());
        result.setTimestamp(System.currentTimeMillis());

        for (Map.Entry<String, DLPPolicy> entry : policies.entrySet()) {
            if (entry.getValue().isEnabled() && entry.getValue().matches(content)) {
                result.addFinding(new DLPFinding(entry.getKey(), entry.getValue().getDescription()));
            }
        }

        return result;
    }

    /**
     * Bloqueia compartilhamento se dados sensíveis detectados
     */
    public boolean canShare(String content, String targetUsers, Identity user) {
        DLPAnalysisResult analysis = analyzeContent(content, user);

        if (!analysis.getFindings().isEmpty()) {
            logSecurityEvent(user, targetUsers, "BLOCKED", analysis);
            return false;
        }

        return true;
    }

    private void logSecurityEvent(Identity user, String targetUsers, String action, DLPAnalysisResult analysis) {
        System.out.println(String.format(
            "[DLP] %s: User=%s, Targets=%s, Findings=%d",
            action, user.getUserId(), targetUsers, analysis.getFindings().size()
        ));
    }

    public void addPolicy(DLPPolicy policy) {
        policies.put(policy.getId(), policy);
    }

    public Collection<DLPPolicy> getPolicies() {
        return policies.values();
    }

    // Inner classes
    public static class DLPPolicy {
        private String id;
        private String description;
        private Pattern pattern;
        private boolean enabled;

        public DLPPolicy(String id, String description, Pattern pattern, boolean enabled) {
            this.id = id;
            this.description = description;
            this.pattern = pattern;
            this.enabled = enabled;
        }

        public boolean matches(String content) {
            return pattern.matcher(content).find();
        }

        // Getters
        public String getId() { return id; }
        public String getDescription() { return description; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class DLPAnalysisResult {
        private String user;
        private long timestamp;
        private List<DLPFinding> findings = new ArrayList<>();

        public void addFinding(DLPFinding finding) { findings.add(finding); }
        public List<DLPFinding> getFindings() { return findings; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }

    public static class DLPFinding {
        private String policyId;
        private String description;

        public DLPFinding(String policyId, String description) {
            this.policyId = policyId;
            this.description = description;
        }

        public String getPolicyId() { return policyId; }
        public String getDescription() { return description; }
    }
}
