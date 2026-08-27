package org.exoplatform.addons;

import java.util.*;

/**
 * Add-on Manager — Permite ativar/desativar add-ons sem reiniciar container
 */
public class AddonManagerService {

  private final Map<String, Addon> addons = new HashMap<>();

  public void registerAddon(String id, String name, String version, boolean enabled) {
    addons.put(id, new Addon(id, name, version, enabled));
  }

  public List<Addon> listAddons() {
    return new ArrayList<>(addons.values());
  }

  public void enableAddon(String addonId) {
    if (addons.containsKey(addonId)) {
      addons.get(addonId).enabled = true;
      // Em produção: notificar PluginManager para recarregar
    }
  }

  public void disableAddon(String addonId) {
    if (addons.containsKey(addonId)) {
      addons.get(addonId).enabled = false;
      // Em produção: notificar PluginManager para descarregar
    }
  }

  public void uploadAddon(String warPath) {
    // Parse WAR metadata
    String addonId = extractAddonId(warPath);
    String version = extractVersion(warPath);

    Addon addon = new Addon(addonId, addonId, version, false);
    addons.put(addonId, addon);
  }

  private String extractAddonId(String warPath) {
    return warPath.replaceAll(".*/(.*)\\.war", "$1");
  }

  private String extractVersion(String warPath) {
    // Extrai versão do nome do arquivo
    java.util.regex.Pattern p = java.util.regex.Pattern.compile("-(\\d+\\.\\d+\\.\\d+)");
    java.util.regex.Matcher m = p.matcher(warPath);
    return m.find() ? m.group(1) : "1.0.0";
  }

  public static class Addon {
    public String id;
    public String name;
    public String version;
    public boolean enabled;
    public long installedAt;

    public Addon(String id, String name, String version, boolean enabled) {
      this.id = id;
      this.name = name;
      this.version = version;
      this.enabled = enabled;
      this.installedAt = System.currentTimeMillis();
    }
  }
}
