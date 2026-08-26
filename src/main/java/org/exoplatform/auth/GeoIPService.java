package org.exoplatform.auth;

import java.net.InetAddress;
import java.util.*;

/**
 * 2FA por Zona — Detecta acesso de IPs em zonas diferentes
 * e exige OTP para novas localizações
 */
public class GeoIPService {

  private static final Map<String, String> IP_TO_ZONE = new HashMap<>();

  static {
    // Simulação de mapeamento IP → Zona (em produção usaria MaxMind GeoIP2)
    IP_TO_ZONE.put("192.168.1", "BR_SP");
    IP_TO_ZONE.put("10.0.0", "BR_SP");
    IP_TO_ZONE.put("172.16.0", "BR_RJ");
  }

  public String getZoneFromIP(String ip) {
    if (ip == null || ip.isEmpty()) {
      return "UNKNOWN";
    }

    // Extrai os 3 primeiros octetos (ex: 192.168.1 de 192.168.1.59)
    String[] parts = ip.split("\\.");
    if (parts.length >= 3) {
      String subnet = parts[0] + "." + parts[1] + "." + parts[2];
      return IP_TO_ZONE.getOrDefault(subnet, "EXTERNAL");
    }

    return "UNKNOWN";
  }

  public boolean isNewZone(String lastKnownZone, String currentZone) {
    if (lastKnownZone == null || lastKnownZone.isEmpty()) {
      return true; // Primeiro acesso
    }
    return !lastKnownZone.equals(currentZone);
  }

  public class ZoneInfo {
    public String zone;
    public String country;
    public String city;

    public ZoneInfo(String zone, String country, String city) {
      this.zone = zone;
      this.country = country;
      this.city = city;
    }
  }
}
