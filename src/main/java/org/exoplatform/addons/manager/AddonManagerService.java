package org.exoplatform.addons.manager;

import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Add-on Manager Service
 * Gerencia, ativa, desativa e atualiza extensões e add-ons
 */
@Service
public class AddonManagerService {

    private final Map<String, Addon> installedAddons = new HashMap<>();
    private final List<String> addonRepository = Arrays.asList(
        "dlp:1.0.0",
        "mfa:1.0.0",
        "anti-malware:2.1.0",
        "anti-brute-force:1.5.0",
        "glpi-integration:3.0.0",
        "exchange-connector:2.0.0",
        "saml-provider:1.2.0",
        "cloud-drive:1.1.0",
        "auto-translate:1.0.0",
        "ai-agent:2.0.0"
    );

    public AddonManagerService() {
        initializeInstalledAddons();
    }

    private void initializeInstalledAddons() {
        installedAddons.put("matrix", new Addon("matrix", "1.0.0", "Matrix (Chat)", true));
        installedAddons.put("jitsi", new Addon("jitsi", "1.0.0", "Jitsi (Videoconferência)", true));
        installedAddons.put("onlyoffice", new Addon("onlyoffice", "1.0.0", "OnlyOffice (Documentos)", true));
        installedAddons.put("glpi-integration", new Addon("glpi-integration", "3.0.0", "GLPI Integration", true));
    }

    /**
     * Lista todos os add-ons disponíveis no repositório
     */
    public List<String> getAvailableAddons() {
        return addonRepository;
    }

    /**
     * Lista todos os add-ons instalados
     */
    public Collection<Addon> getInstalledAddons() {
        return installedAddons.values();
    }

    /**
     * Ativa um add-on
     */
    public boolean enableAddon(String addonId) {
        Addon addon = installedAddons.get(addonId);
        if (addon != null) {
            addon.setEnabled(true);
            logAction("ENABLE", addonId);
            return true;
        }
        return false;
    }

    /**
     * Desativa um add-on
     */
    public boolean disableAddon(String addonId) {
        Addon addon = installedAddons.get(addonId);
        if (addon != null) {
            addon.setEnabled(false);
            logAction("DISABLE", addonId);
            return true;
        }
        return false;
    }

    /**
     * Instala um novo add-on
     */
    public boolean installAddon(String addonId, String version) {
        if (installedAddons.containsKey(addonId)) {
            return false; // Já instalado
        }

        String addonSpec = addonId + ":" + version;
        if (!addonRepository.contains(addonSpec)) {
            return false; // Não encontrado no repositório
        }

        Addon addon = new Addon(addonId, version, addonId, true);
        installedAddons.put(addonId, addon);
        logAction("INSTALL", addonId + ":" + version);

        return true;
    }

    /**
     * Desinstala um add-on
     */
    public boolean uninstallAddon(String addonId) {
        if (installedAddons.remove(addonId) != null) {
            logAction("UNINSTALL", addonId);
            return true;
        }
        return false;
    }

    /**
     * Atualiza um add-on
     */
    public boolean updateAddon(String addonId, String newVersion) {
        Addon addon = installedAddons.get(addonId);
        if (addon != null) {
            String oldVersion = addon.getVersion();
            addon.setVersion(newVersion);
            addon.setUpdatedAt(System.currentTimeMillis());
            logAction("UPDATE", addonId + ":" + oldVersion + " -> " + newVersion);
            return true;
        }
        return false;
    }

    /**
     * Obtém info de um add-on específico
     */
    public Addon getAddonInfo(String addonId) {
        return installedAddons.get(addonId);
    }

    private void logAction(String action, String addon) {
        System.out.println(String.format("[ADDON-MANAGER] %s: %s", action, addon));
    }

    // Inner class
    public static class Addon {
        private String id;
        private String version;
        private String name;
        private boolean enabled;
        private long installedAt;
        private long updatedAt;

        public Addon(String id, String version, String name, boolean enabled) {
            this.id = id;
            this.version = version;
            this.name = name;
            this.enabled = enabled;
            this.installedAt = System.currentTimeMillis();
            this.updatedAt = System.currentTimeMillis();
        }

        // Getters and setters
        public String getId() { return id; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getName() { return name; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getInstalledAt() { return installedAt; }
        public long getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    }
}
