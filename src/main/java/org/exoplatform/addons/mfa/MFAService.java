package org.exoplatform.addons.mfa;

import org.springframework.stereotype.Service;
import java.util.*;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Multi-Factor Authentication Service with Zone-based Configuration
 * Autenticação em Dois Fatores configurável por zona/localização
 */
@Service
public class MFAService {

    private final Map<String, ZoneMFAPolicy> zonePolicies = new HashMap<>();
    private final Map<String, UserMFASession> activeSessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    public MFAService() {
        initializeZones();
    }

    private void initializeZones() {
        // Zone: Headquarters (SP)
        zonePolicies.put("HQ_SP", new ZoneMFAPolicy(
            "HQ_SP",
            "Headquarters - São Paulo",
            true,  // 2FA required
            300,   // 5 min timeout
            Arrays.asList("OTP", "EMAIL", "SMS")
        ));

        // Zone: Remote
        zonePolicies.put("REMOTE", new ZoneMFAPolicy(
            "REMOTE",
            "Remote/Home Office",
            true,
            600,   // 10 min timeout
            Arrays.asList("OTP", "EMAIL")
        ));

        // Zone: Public
        zonePolicies.put("PUBLIC", new ZoneMFAPolicy(
            "PUBLIC",
            "Public/Untrusted Network",
            true,
            180,   // 3 min timeout - more strict
            Arrays.asList("OTP")  // Only OTP for public
        ));
    }

    /**
     * Verifica se MFA é requerido para a zona
     */
    public boolean isMFARequired(String zone) {
        ZoneMFAPolicy policy = zonePolicies.get(zone);
        return policy != null && policy.isEnabled();
    }

    /**
     * Gera código OTP (One-Time Password)
     */
    public String generateOTP(String userId, String zone) {
        String otp = String.format("%06d", random.nextInt(1000000));
        ZoneMFAPolicy policy = zonePolicies.get(zone);

        UserMFASession session = new UserMFASession();
        session.setUserId(userId);
        session.setZone(zone);
        session.setOtp(otp);
        session.setCreatedAt(Instant.now());
        session.setExpiresAt(Instant.now().plusSeconds(policy.getTimeoutSeconds()));
        session.setAttempts(0);

        activeSessions.put(userId + ":" + zone, session);

        return otp;
    }

    /**
     * Valida código OTP
     */
    public boolean validateOTP(String userId, String zone, String code) {
        UserMFASession session = activeSessions.get(userId + ":" + zone);

        if (session == null) {
            return false;
        }

        if (Instant.now().isAfter(session.getExpiresAt())) {
            activeSessions.remove(userId + ":" + zone);
            return false;
        }

        session.incrementAttempts();
        if (session.getAttempts() > 3) {
            activeSessions.remove(userId + ":" + zone);
            logSecurityEvent(userId, zone, "MFA_FAILED_MAX_ATTEMPTS");
            return false;
        }

        if (session.getOtp().equals(code)) {
            logSecurityEvent(userId, zone, "MFA_SUCCESS");
            activeSessions.remove(userId + ":" + zone);
            return true;
        }

        return false;
    }

    /**
     * Envia OTP via email
     */
    public void sendOTPEmail(String userId, String email, String otp, String zone) {
        String subject = String.format("Código de Autenticação eXo - %s", zone);
        String body = String.format(
            "Seu código de autenticação é: %s\nVálido por 5 minutos.\nNunca compartilhe este código.",
            otp
        );
        System.out.println(String.format("[MFA] Email enviado: %s -> %s (OTP: %s)", userId, email, otp));
    }

    /**
     * Envia OTP via SMS (integração com provedor)
     */
    public void sendOTPSMS(String userId, String phone, String otp, String zone) {
        System.out.println(String.format("[MFA] SMS enviado: %s -> %s (OTP: %s)", userId, phone, otp));
    }

    private void logSecurityEvent(String userId, String zone, String action) {
        System.out.println(String.format("[MFA] %s: User=%s, Zone=%s", action, userId, zone));
    }

    public ZoneMFAPolicy getZonePolicy(String zone) {
        return zonePolicies.get(zone);
    }

    public Collection<ZoneMFAPolicy> getAllPolicies() {
        return zonePolicies.values();
    }

    // Inner classes
    public static class ZoneMFAPolicy {
        private String id;
        private String name;
        private boolean enabled;
        private int timeoutSeconds;
        private List<String> methods;

        public ZoneMFAPolicy(String id, String name, boolean enabled, int timeout, List<String> methods) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
            this.timeoutSeconds = timeout;
            this.methods = methods;
        }

        // Getters
        public String getId() { return id; }
        public String getName() { return name; }
        public boolean isEnabled() { return enabled; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public List<String> getMethods() { return methods; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class UserMFASession {
        private String userId;
        private String zone;
        private String otp;
        private Instant createdAt;
        private Instant expiresAt;
        private int attempts;

        // Getters and setters
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getZone() { return zone; }
        public void setZone(String zone) { this.zone = zone; }
        public String getOtp() { return otp; }
        public void setOtp(String otp) { this.otp = otp; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
        public int getAttempts() { return attempts; }
        // ACRESCENTADO 2026-08-27: generateOTP() ja' chamava setAttempts(0) na
        // linha 73, mas o metodo nunca existiu — era o unico erro de compilacao
        // do modulo depois de corrigida a coordenada Maven da dependencia.
        public void setAttempts(int attempts) { this.attempts = attempts; }
        public void incrementAttempts() { this.attempts++; }
    }
}
