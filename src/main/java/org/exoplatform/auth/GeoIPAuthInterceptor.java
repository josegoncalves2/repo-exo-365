package org.exoplatform.auth;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Interceptor de login que valida zona geográfica
 * Se IP é de zona diferente, exige OTP antes de liberar login
 */
@Component
public class GeoIPAuthInterceptor {

  private final GeoIPService geoIPService = new GeoIPService();
  private final Map<String, UserLastLogin> userLastLoginMap = new HashMap<>();

  public boolean validateZoneAccess(String userId, String loginIP, boolean twoFactorRequired) {
    String currentZone = geoIPService.getZoneFromIP(loginIP);
    UserLastLogin lastLogin = userLastLoginMap.getOrDefault(userId, null);

    if (lastLogin == null) {
      // Primeiro acesso deste usuário
      recordLogin(userId, currentZone, loginIP);
      return true;
    }

    if (geoIPService.isNewZone(lastLogin.zone, currentZone)) {
      if (twoFactorRequired) {
        // Nova zona detectada — exigir OTP
        return false; // Bloqueado até OTP ser inserido
      }
    }

    recordLogin(userId, currentZone, loginIP);
    return true;
  }

  public void recordLogin(String userId, String zone, String ip) {
    userLastLoginMap.put(userId, new UserLastLogin(zone, ip, System.currentTimeMillis()));
  }

  public static class UserLastLogin {
    public String zone;
    public String ip;
    public long timestamp;

    public UserLastLogin(String zone, String ip, long timestamp) {
      this.zone = zone;
      this.ip = ip;
      this.timestamp = timestamp;
    }
  }
}
