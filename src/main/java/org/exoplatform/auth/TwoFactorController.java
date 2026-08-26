package org.exoplatform.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/portal/rest/v1/auth")
public class TwoFactorController {

  private final GeoIPAuthInterceptor geoIPInterceptor = new GeoIPAuthInterceptor();
  private final Map<String, String> otpMap = new HashMap<>();

  @PostMapping("/2fa/validate-zone")
  public ResponseEntity<?> validateZone(@RequestParam String userId,
                                       @RequestParam String loginIP) {
    boolean allowed = geoIPInterceptor.validateZoneAccess(userId, loginIP, true);

    if (!allowed) {
      String otp = generateOTP();
      otpMap.put(userId, otp);
      // Em produção: enviar via SMS/email
      return ResponseEntity.status(403).body(new TwoFactorResponse(
        "OTP_REQUIRED",
        "Nova localização detectada. Código enviado via SMS."
      ));
    }

    return ResponseEntity.ok(new TwoFactorResponse("SUCCESS", "Acesso autorizado"));
  }

  @PostMapping("/2fa/verify-otp")
  public ResponseEntity<?> verifyOTP(@RequestParam String userId,
                                    @RequestParam String otp) {
    String storedOTP = otpMap.get(userId);
    if (storedOTP != null && storedOTP.equals(otp)) {
      otpMap.remove(userId);
      return ResponseEntity.ok(new TwoFactorResponse("SUCCESS", "2FA verificado"));
    }
    return ResponseEntity.status(401).body(new TwoFactorResponse("INVALID_OTP", "Código incorreto"));
  }

  private String generateOTP() {
    Random random = new Random();
    int otp = 100000 + random.nextInt(900000); // 6 dígitos
    return String.valueOf(otp);
  }

  public static class TwoFactorResponse {
    public String status;
    public String message;

    public TwoFactorResponse(String status, String message) {
      this.status = status;
      this.message = message;
    }
  }
}
