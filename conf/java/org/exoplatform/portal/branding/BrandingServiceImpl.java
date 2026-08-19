/*
 * PATCH 2026-08-19: descompilado com CFR 0.152 a partir de
 * portal.component.portal.jar (imagem oficial eXo/Meeds 7.2.1) e recompilado
 * com uma unica mudanca funcional, em updateBrandingInformation(): a tela
 * "Idioma padrao" da Administracao chama PUT /v1/platform/branding, que cai
 * aqui -- e este metodo atualiza TODOS os campos do branding (companyName,
 * logo, tema...) EXCETO defaultLanguage, que nunca era escrito em lugar
 * nenhum. Prova: teste de controle comparando com companyName (persiste) no
 * mesmo endpoint -- a UI mostra "salvo com sucesso" (HTTP 204) mas o valor
 * nunca muda. LocaleConfigService.saveDefaultLocaleConfig() ja existe,
 * completo e correto (grava no SettingService, invalida cache, dispara
 * evento) -- so' faltava a chamada. saveDefaultLocaleConfig() valida contra
 * chaves com HIFEN ("pt-BR"); a API/DTO usa UNDERSCORE ("pt_BR") -- por isso
 * o patch tambem normaliza o separador antes de chamar, senao os idiomas com
 * variante regional (a maioria dos que tem pais, inclusive pt-BR, o idioma
 * desta instalacao) lancariam IllegalArgumentException.
 *
 * Os outros 3 metodos privados com `throws` adicionado (updateBrandingFile-
 * ByInputStream, updateBrandingFileByUploadId, retrieveDefaultBrandingFile)
 * NAO SAO PARTE DO BUG -- sao artefato do descompilador, que perdeu a
 * clausula `throws` original ao nao conseguir resolver FileItem/
 * FileStorageException (classes fora do seu classpath). Conferido com javap
 * contra o .class original: e' a UNICA diferenca na API publica+privada
 * inteira da classe, e nenhum metodo publico mudou de assinatura.
 *
 * ---- cabecalho original do CFR, abaixo ----
 *
 * Could not load the following classes:
 *  com.github.sommeri.less4j.Less4jException
 *  com.github.sommeri.less4j.LessCompiler$CompilationResult
 *  com.github.sommeri.less4j.LessCompiler$Configuration
 *  com.github.sommeri.less4j.core.ThreadUnsafeLessCompiler
 *  org.apache.commons.lang3.StringUtils
 *  org.exoplatform.commons.api.settings.ExoFeatureService
 *  org.exoplatform.commons.api.settings.SettingService
 *  org.exoplatform.commons.api.settings.SettingValue
 *  org.exoplatform.commons.api.settings.data.Context
 *  org.exoplatform.commons.api.settings.data.Scope
 *  org.exoplatform.commons.file.model.FileItem
 *  org.exoplatform.commons.file.services.FileService
 *  org.exoplatform.commons.file.services.FileStorageException
 *  org.exoplatform.commons.utils.IOUtil
 *  org.exoplatform.container.PortalContainer
 *  org.exoplatform.container.configuration.ConfigurationManager
 *  org.exoplatform.container.xml.InitParams
 *  org.exoplatform.container.xml.ValueParam
 *  org.exoplatform.container.xml.ValuesParam
 *  org.exoplatform.portal.branding.BrandingService
 *  org.exoplatform.portal.branding.model.Background
 *  org.exoplatform.portal.branding.model.Branding
 *  org.exoplatform.portal.branding.model.BrandingFile
 *  org.exoplatform.portal.branding.model.Favicon
 *  org.exoplatform.portal.branding.model.Logo
 *  org.exoplatform.portal.config.UserACL
 *  org.exoplatform.services.listener.ListenerService
 *  org.exoplatform.services.log.ExoLogger
 *  org.exoplatform.services.log.Log
 *  org.exoplatform.services.resources.LocaleConfig
 *  org.exoplatform.services.resources.LocaleConfigService
 *  org.exoplatform.upload.UploadResource
 *  org.exoplatform.upload.UploadService
 *  org.picocontainer.Startable
 */
package org.exoplatform.portal.branding;

import com.github.sommeri.less4j.Less4jException;
import com.github.sommeri.less4j.LessCompiler;
import com.github.sommeri.less4j.core.ThreadUnsafeLessCompiler;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.commons.file.services.FileStorageException;
import org.exoplatform.commons.utils.IOUtil;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ValueParam;
import org.exoplatform.container.xml.ValuesParam;
import org.exoplatform.portal.branding.BrandingService;
import org.exoplatform.portal.branding.model.Background;
import org.exoplatform.portal.branding.model.Branding;
import org.exoplatform.portal.branding.model.BrandingFile;
import org.exoplatform.portal.branding.model.Favicon;
import org.exoplatform.portal.branding.model.Logo;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.resources.LocaleConfig;
import org.exoplatform.services.resources.LocaleConfigService;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;
import org.picocontainer.Startable;

public class BrandingServiceImpl
implements BrandingService,
Startable {
    private static final Log LOG = ExoLogger.getExoLogger(BrandingServiceImpl.class);
    public static final String BRANDING_RESET_ATTACHMENT_ID = "0";
    public static final String BRANDING_LOGO_BASE_PATH = "/portal/rest/v1/platform/branding/logo?v=";
    public static final String BRANDING_FAVICON_BASE_PATH = "/portal/rest/v1/platform/branding/favicon?v=";
    public static final String BRANDING_LOGIN_BG_BASE_PATH = "/portal/rest/v1/platform/branding/loginBackground?v=";
    public static final String BRANDING_PAGE_BG_BASE_PATH = "/portal/rest/v1/platform/branding/pageBackground?v=";
    public static final String BRANDING_TOP_BAR_BG_BASE_PATH = "/portal/rest/v1/platform/branding/topBarBackground?v=";
    public static final String BRANDING_SIDEBAR_BG_BASE_PATH = "/portal/rest/v1/platform/branding/sideBarBackground?v=";
    public static final String BRANDING_DRAWER_BG_BASE_PATH = "/portal/rest/v1/platform/branding/drawerBackground?v=";
    public static final String BRANDING_COMPANY_NAME_INIT_PARAM = "exo.branding.company.name";
    public static final String BRANDING_SITE_NAME_INIT_PARAM = "exo.branding.company.siteName";
    public static final String BRANDING_COMPANY_LINK_INIT_PARAM = "exo.branding.company.link";
    public static final String BRANDING_LOGIN_TITLE_PARAM = "authentication.title";
    public static final String BRANDING_LOGIN_SUBTITLE_PARAM = "authentication.subtitle";
    public static final String BRANDING_LOGIN_BG_INIT_PARAM = "authentication.background";
    public static final String BRANDING_LOGO_INIT_PARAM = "exo.branding.company.logo";
    public static final String BRANDING_FAVICON_INIT_PARAM = "exo.branding.company.favicon";
    public static final String BRANDING_COMPANY_NAME_SETTING_KEY = "exo.branding.company.name";
    public static final String BRANDING_SITE_NAME_SETTING_KEY = "exo.branding.company.siteName";
    public static final String BRANDING_LOGIN_TEXT_COLOR_KEY = "authentication.loginBackgroundTextColor";
    public static final String BRANDING_LOGIN_ALT_TEXT_KEY = "authentication.loginBackgroundAltText";
    public static final String BRANDING_TITLE_SETTING_KEY = "authentication.title.";
    public static final String BRANDING_SUBTITLE_SETTING_KEY = "authentication.subtitle.";
    public static final String BRANDING_COMPANY_LINK_SETTING_KEY = "exo.branding.company.link";
    public static final String BRANDING_THEME_LESS_PATH = "exo.branding.theme.path";
    public static final String BRANDING_THEME_VARIABLES = "exo.branding.theme.variables";
    public static final String BRANDING_LOGO_ID_SETTING_KEY = "exo.branding.company.id";
    public static final String BRANDING_FAVICON_ID_SETTING_KEY = "exo.branding.company.favicon.id";
    public static final String BRANDING_LOGIN_BG_ID_SETTING_KEY = "authentication.background";
    public static final String BRANDING_PAGE_BG_ID_SETTING_KEY = "page.background";
    public static final String BRANDING_TOP_BAR_BG_ID_SETTING_KEY = "topBar.background";
    public static final String BRANDING_SIDEBAR_BG_ID_SETTING_KEY = "sideBar.background";
    public static final String BRANDING_DRAWER_BG_ID_SETTING_KEY = "drawer.background";
    public static final String TOP_BAR_BG_IMAGE_THEME_STYLE_KEY = "topBarBackgroundImage";
    public static final String BRANDING_CUSTOM_CSS = "page.customCss";
    public static final String SIDEBAR_BG_IMAGE_THEME_STYLE_KEY = "sideBarBackgroundImage";
    public static final String DRAWER_BG_IMAGE_THEME_STYLE_KEY = "drawerBackgroundImage";
    public static final String BRANDING_PAGE_BG_COLOR_KEY = "page.backgroundColor";
    public static final String BRANDING_PAGE_BG_REPEAT_KEY = "page.backgroundRepeat";
    public static final String BRANDING_PAGE_BG_EFFECT_KEY = "page.backgroundEffect";
    public static final String BRANDING_PAGE_WIDTH_KEY = "page.width";
    public static final String BRANDING_PAGE_BG_POSITION_KEY = "page.backgroundPosition";
    public static final String BRANDING_PAGE_BG_SIZE_KEY = "page.backgroundSize";
    public static final String BRANDING_CUSTOM_STYLE_FEATURE = "customStylesheet";
    public static final String BRANDING_LAST_UPDATED_TIME_KEY = "branding.lastUpdatedTime";
    public static final String FILE_API_NAME_SPACE = "CompanyBranding";
    public static final String LOGO_NAME = "logo.png";
    public static final String FAVICON_NAME = "favicon.ico";
    public static final String LOGIN_BACKGROUND_NAME = "loginBackground.png";
    public static final String TOP_BAR_BACKGROUND_NAME = "topBarBackground.png";
    public static final String SIDEBAR_BACKGROUND_NAME = "sideBarBackground.png";
    public static final String DRAWER_BACKGROUND_NAME = "drawerBackground.png";
    public static final String PAGE_BACKGROUND_NAME = "pageBackground.png";
    public static final String BRANDING_DEFAULT_LOGO_PATH = "/skin/images/logo/DefaultLogo.png";
    public static final String BRANDING_DEFAULT_FAVICON_PATH = "/skin/images/favicon.ico";
    public static final Context BRANDING_CONTEXT = Context.GLOBAL.id("BRANDING");
    public static final Scope BRANDING_SCOPE = Scope.APPLICATION.id("BRANDING");
    public static final long DEFAULT_LAST_MODIFED = System.currentTimeMillis();
    private PortalContainer container;
    private LocaleConfigService localeConfigService;
    private SettingService settingService;
    private FileService fileService;
    private UploadService uploadService;
    private ConfigurationManager configurationManager;
    private ExoFeatureService featureService;
    private ListenerService listenerService;
    private UserACL userAcl;
    private String defaultCompanyName = "";
    private String defaultSiteName = "";
    private String defaultCompanyLink = "";
    private String defaultConfiguredLogoPath = null;
    private String defaultConfiguredFaviconPath = null;
    private String defaultConfiguredLoginBgPath = null;
    private String lessFilePath = null;
    private Map<String, String> themeVariables = null;
    private String defaultLoginTitle = null;
    private String defaultLoginSubtitle = null;
    private Map<String, String> loginTitle = null;
    private Map<String, String> loginSubtitle = null;
    private Map<String, String> supportedLanguages = null;
    private String lessThemeContent = null;
    private String themeCSSContent = null;
    private Logo logo = null;
    private String customCss = null;
    private Favicon favicon = null;
    private Background loginBackground = null;
    private Background pageBackground = null;
    private Background topBarBackground = null;
    private Background sideBarBackground = null;
    private Background drawerBackground = null;

    public BrandingServiceImpl(PortalContainer container, ConfigurationManager configurationManager, SettingService settingService, FileService fileService, UploadService uploadService, LocaleConfigService localeConfigService, ListenerService listenerService, UserACL userAcl, InitParams initParams) {
        this.container = container;
        this.configurationManager = configurationManager;
        this.settingService = settingService;
        this.fileService = fileService;
        this.uploadService = uploadService;
        this.localeConfigService = localeConfigService;
        this.listenerService = listenerService;
        this.userAcl = userAcl;
        this.loadLanguages();
        this.loadInitParams(initParams);
    }

    public void start() {
        this.computeThemeCSS();
        this.listenerService.addListener("feature.status.changed", e -> {
            if (StringUtils.equals((CharSequence)BRANDING_CUSTOM_STYLE_FEATURE, (CharSequence)((String)e.getSource()))) {
                this.triggerBrandingUpdated(true, true);
            }
        });
    }

    public void stop() {
    }

    public String getThemeCSSContent() {
        if (this.themeCSSContent == null) {
            this.computeThemeCSS();
        }
        return this.themeCSSContent;
    }

    public Branding getBrandingInformation() {
        return this.getBrandingInformation(true);
    }

    public Branding getBrandingInformation(boolean retrieveBinaries) {
        Branding branding = new Branding();
        branding.setDefaultLanguage(this.getDefaultLanguage());
        branding.setDirection(this.getDefaultLocaleDirection());
        branding.setSupportedLanguages(this.loadLanguages());
        branding.setCompanyName(this.getCompanyName());
        branding.setCompanyLink(this.getCompanyLink());
        branding.setSiteName(this.getSiteName());
        branding.setLogo(this.getLogo());
        branding.setFavicon(this.getFavicon());
        branding.setLoginBackground(this.getLoginBackground());
        branding.setTopBarBackground(this.getTopBarBackground());
        branding.setSideBarBackground(this.getSideBarBackground());
        branding.setDrawerBackground(this.getDrawerBackground());
        branding.setLoginBackgroundTextColor(this.getLoginBackgroundTextColor());
        branding.setLoginBackgroundAltText(this.getLoginBackgroundAltText());
        branding.setPageBackground(this.getPageBackground());
        branding.setPageBackgroundColor(this.getPageBackgroundColor());
        branding.setPageBackgroundPosition(this.getPageBackgroundPosition());
        branding.setPageBackgroundSize(this.getPageBackgroundSize());
        branding.setPageBackgroundRepeat(this.getPageBackgroundRepeat());
        branding.setPageBackgroundEffect(this.getPageBackgroundEffect());
        branding.setPageWidth(this.getPageWidth());
        branding.setCustomCss(this.getCustomCss());
        branding.setThemeStyle(this.getThemeStyle());
        branding.setLoginTitle(this.getLoginTitle());
        branding.setLoginSubtitle(this.getLoginSubtitle());
        branding.setLastUpdatedTime(this.getLastUpdatedTime());
        if (!retrieveBinaries) {
            if (branding.getFavicon() != null && branding.getFavicon().getData() != null) {
                Favicon brandingFile = branding.getFavicon().clone();
                brandingFile.setData(null);
                branding.setFavicon(brandingFile);
            }
            if (branding.getLogo() != null && branding.getLogo().getData() != null) {
                Logo brandingFile = branding.getLogo().clone();
                brandingFile.setData(null);
                branding.setLogo(brandingFile);
            }
            if (branding.getLoginBackground() != null && branding.getLoginBackground().getData() != null) {
                Background brandingFile = branding.getLoginBackground().clone();
                brandingFile.setData(null);
                branding.setLoginBackground(brandingFile);
            }
            if (branding.getPageBackground() != null && branding.getPageBackground().getData() != null) {
                Background brandingFile = branding.getPageBackground().clone();
                brandingFile.setData(null);
                branding.setPageBackground(brandingFile);
            }
            if (branding.getTopBarBackground() != null && branding.getTopBarBackground().getData() != null) {
                Background brandingFile = branding.getTopBarBackground().clone();
                brandingFile.setData(null);
                branding.setTopBarBackground(brandingFile);
            }
            if (branding.getSideBarBackground() != null && branding.getSideBarBackground().getData() != null) {
                Background brandingFile = branding.getSideBarBackground().clone();
                brandingFile.setData(null);
                branding.setSideBarBackground(brandingFile);
            }
            if (branding.getDrawerBackground() != null && branding.getDrawerBackground().getData() != null) {
                Background brandingFile = branding.getDrawerBackground().clone();
                brandingFile.setData(null);
                branding.setDrawerBackground(brandingFile);
            }
        }
        return branding;
    }

    public long getLastUpdatedTime() {
        String lastUpdatedTime = this.getPropertyValue(BRANDING_LAST_UPDATED_TIME_KEY);
        if (lastUpdatedTime == null) {
            return DEFAULT_LAST_MODIFED;
        }
        return Long.parseLong(lastUpdatedTime);
    }

    public void updateBrandingInformation(Branding branding) {
        this.validateCSSInputs(branding);
        try {
            if (StringUtils.isNotBlank((CharSequence)branding.getDefaultLanguage())) {
                this.localeConfigService.saveDefaultLocaleConfig(branding.getDefaultLanguage().replace('_', '-'));
            }
            this.updateCompanyName(branding.getCompanyName(), false);
            this.updateSiteName(branding.getSiteName(), false);
            this.updateCompanyLink(branding.getCompanyLink(), false);
            this.updateLogo(branding.getLogo(), false);
            this.updateFavicon(branding.getFavicon(), false);
            this.updateLoginBackground(branding.getLoginBackground(), false);
            this.updateTopBarBackground(branding.getTopBarBackground(), false);
            this.updateSideBarBackground(branding.getSideBarBackground(), false);
            this.updateDrawerBackground(branding.getDrawerBackground(), false);
            this.updateLoginBackgroundTextColor(branding.getLoginBackgroundTextColor(), false);
            this.updateLoginBackgroundAltText(branding.getLoginBackgroundAltText(), false);
            this.updatePageBackground(branding.getPageBackground(), false);
            this.updatePageBackgroundColor(branding.getPageBackgroundColor(), false);
            this.updatePageBackgroundSize(branding.getPageBackgroundSize(), false);
            this.updatePageBackgroundPosition(branding.getPageBackgroundPosition(), false);
            this.updatePageBackgroundEffect(branding.getPageBackgroundEffect(), false);
            this.updatePageBackgroundRepeat(branding.getPageBackgroundRepeat(), false);
            this.updatePageWidth(branding.getPageWidth(), false);
            this.updateCustomCss(branding.getCustomCss(), false);
            Map themeStyles = branding.getThemeStyle();
            this.processThemeBackgroundImages(themeStyles);
            this.updateThemeStyle(themeStyles, false);
            this.updateLoginTitle(branding.getLoginTitle());
            this.updateLoginSubtitle(branding.getLoginSubtitle());
        }
        finally {
            this.triggerBrandingUpdated(true, true);
        }
    }

    public String getCompanyName() {
        SettingValue brandingCompanyName = this.settingService.get(Context.GLOBAL, Scope.GLOBAL, "exo.branding.company.name");
        if (brandingCompanyName != null && StringUtils.isNotBlank((CharSequence)((CharSequence)brandingCompanyName.getValue()))) {
            return (String)brandingCompanyName.getValue();
        }
        return this.defaultCompanyName;
    }

    public String getCompanyLink() {
        return this.getPropertyValue("exo.branding.company.link", this.defaultCompanyLink);
    }

    public String getSiteName() {
        return this.getPropertyValue("exo.branding.company.siteName", this.defaultSiteName);
    }

    public String getPageBackgroundColor() {
        return this.getPropertyValue(BRANDING_PAGE_BG_COLOR_KEY);
    }

    public String getPageBackgroundRepeat() {
        return this.getPropertyValue(BRANDING_PAGE_BG_REPEAT_KEY);
    }

    public String getPageBackgroundEffect() {
        return this.getPropertyValue(BRANDING_PAGE_BG_EFFECT_KEY);
    }

    public String getPageBackgroundImageUrl() {
        if (this.getPageBackgroundEffect() == null && this.getPageBackgroundPath() == null) {
            return null;
        }
        StringBuilder backgroundImageUrl = new StringBuilder();
        if (this.getPageBackgroundPath() != null) {
            backgroundImageUrl.append("url(");
            backgroundImageUrl.append(this.getPageBackgroundPath());
            if (this.getPageBackgroundEffect() != null) {
                backgroundImageUrl.append("), ");
                backgroundImageUrl.append(this.getPageBackgroundEffect());
                backgroundImageUrl.append(";");
            } else {
                backgroundImageUrl.append(");");
            }
        } else if (this.getPageBackgroundEffect() != null) {
            return this.getPageBackgroundEffect().concat("; ");
        }
        return backgroundImageUrl.toString();
    }

    public String getPageWidth() {
        return this.getPropertyValue(BRANDING_PAGE_WIDTH_KEY);
    }

    public String getCustomCss() {
        return this.getPropertyValue(BRANDING_CUSTOM_CSS);
    }

    public String getPageBackgroundPosition() {
        return this.getPropertyValue(BRANDING_PAGE_BG_POSITION_KEY);
    }

    public String getPageBackgroundSize() {
        return this.getPropertyValue(BRANDING_PAGE_BG_SIZE_KEY);
    }

    public String getLoginBackgroundTextColor() {
        return this.getPropertyValue(BRANDING_LOGIN_TEXT_COLOR_KEY);
    }

    public String getLoginBackgroundAltText() {
        return this.getPropertyValue(BRANDING_LOGIN_ALT_TEXT_KEY);
    }

    public void updateCompanyName(String companyName) {
        this.updateCompanyName(companyName, true);
    }

    public void updateCompanyLink(String companyLink) {
        this.updateCompanyLink(companyLink, true);
    }

    public void updateSiteName(String siteName) {
        this.updateSiteName(siteName, true);
    }

    public void updateLoginBackgroundTextColor(String textColor) {
        this.updateLoginBackgroundTextColor(textColor, true);
    }

    public void updateLoginBackgroundAltText(String altText) {
        this.updateLoginBackgroundAltText(altText, true);
    }

    public Long getLogoId() {
        return this.getPropertyValueLong(BRANDING_LOGO_ID_SETTING_KEY);
    }

    public Long getFaviconId() {
        return this.getPropertyValueLong(BRANDING_FAVICON_ID_SETTING_KEY);
    }

    public Long getLoginBackgroundId() {
        return this.getPropertyValueLong("authentication.background");
    }

    public Long getPageBackgroundId() {
        return this.getPropertyValueLong(BRANDING_PAGE_BG_ID_SETTING_KEY);
    }

    public Long getTopBarBackgroundId() {
        return this.getPropertyValueLong(BRANDING_TOP_BAR_BG_ID_SETTING_KEY);
    }

    public Long getSideBarBackgroundId() {
        return this.getPropertyValueLong(BRANDING_SIDEBAR_BG_ID_SETTING_KEY);
    }

    public Long getDrawerBackgroundId() {
        return this.getPropertyValueLong(BRANDING_DRAWER_BG_ID_SETTING_KEY);
    }

    public Logo getLogo() {
        if (this.logo == null) {
            try {
                Long imageId = this.getLogoId();
                this.logo = imageId != null && imageId > 0L ? this.retrieveStoredBrandingFile(imageId, new Logo()) : this.retrieveDefaultBrandingFile(this.defaultConfiguredLogoPath, new Logo(), LOGO_NAME, BRANDING_LOGO_ID_SETTING_KEY);
            }
            catch (Exception e) {
                LOG.warn((Object)"Error retrieving logo", (Throwable)e);
            }
        }
        return this.logo;
    }

    public Favicon getFavicon() {
        if (this.favicon == null) {
            try {
                Long imageId = this.getFaviconId();
                this.favicon = imageId != null ? this.retrieveStoredBrandingFile(imageId, new Favicon()) : this.retrieveDefaultBrandingFile(this.defaultConfiguredFaviconPath, new Favicon(), FAVICON_NAME, BRANDING_FAVICON_ID_SETTING_KEY);
            }
            catch (Exception e) {
                LOG.warn((Object)"Error retrieving favicon", (Throwable)e);
            }
        }
        return this.favicon;
    }

    public Background getLoginBackground() {
        if (this.loginBackground == null) {
            try {
                Long imageId = this.getLoginBackgroundId();
                this.loginBackground = imageId != null ? this.retrieveStoredBrandingFile(imageId, new Background()) : (StringUtils.isNotBlank((CharSequence)this.defaultConfiguredLoginBgPath) ? this.retrieveDefaultBrandingFile(this.defaultConfiguredLoginBgPath, new Background(), LOGIN_BACKGROUND_NAME, "authentication.background") : new Background());
            }
            catch (Exception e) {
                LOG.warn((Object)"Error retrieving login background", (Throwable)e);
            }
        }
        return this.loginBackground;
    }

    public Background getPageBackground() {
        if (this.pageBackground == null) {
            try {
                Long imageId = this.getPageBackgroundId();
                this.pageBackground = imageId != null ? this.retrieveStoredBrandingFile(imageId, new Background()) : new Background();
            }
            catch (Exception e) {
                LOG.warn((Object)"Error retrieving page background", (Throwable)e);
            }
        }
        return this.pageBackground;
    }

    public Background getTopBarBackground() {
        if (this.topBarBackground == null) {
            try {
                Long imageId = this.getTopBarBackgroundId();
                this.topBarBackground = imageId != null ? this.retrieveStoredBrandingFile(imageId, new Background()) : new Background();
            }
            catch (Exception e) {
                LOG.warn((Object)"Error retrieving top bar background", (Throwable)e);
            }
        }
        return this.topBarBackground;
    }

    public Background getSideBarBackground() {
        if (this.sideBarBackground == null) {
            try {
                Long imageId = this.getSideBarBackgroundId();
                this.sideBarBackground = imageId != null ? this.retrieveStoredBrandingFile(imageId, new Background()) : new Background();
            }
            catch (Exception e) {
                LOG.warn((Object)"Error retrieving sidebar background", (Throwable)e);
            }
        }
        return this.sideBarBackground;
    }

    public Background getDrawerBackground() {
        if (this.drawerBackground == null) {
            try {
                Long imageId = this.getDrawerBackgroundId();
                this.drawerBackground = imageId != null ? this.retrieveStoredBrandingFile(imageId, new Background()) : new Background();
            }
            catch (Exception e) {
                LOG.warn((Object)"Error retrieving drawer background", (Throwable)e);
            }
        }
        return this.drawerBackground;
    }

    public String getLogoPath() {
        Logo brandingLogo = this.getLogo();
        return brandingLogo == null || brandingLogo.getData() == null ? null : BRANDING_LOGO_BASE_PATH + Objects.hash(brandingLogo.getUpdatedDate());
    }

    public String getFaviconPath() {
        Favicon brandingFavicon = this.getFavicon();
        return brandingFavicon == null || brandingFavicon.getData() == null ? null : BRANDING_FAVICON_BASE_PATH + Objects.hash(brandingFavicon.getUpdatedDate());
    }

    public String getLoginBackgroundPath() {
        Background background = this.getLoginBackground();
        return background == null || background.getData() == null ? null : BRANDING_LOGIN_BG_BASE_PATH + Objects.hash(background.getUpdatedDate());
    }

    public String getPageBackgroundPath() {
        Background background = this.getPageBackground();
        return background == null || background.getData() == null ? null : BRANDING_PAGE_BG_BASE_PATH + Objects.hash(background.getUpdatedDate());
    }

    public String getTopBarBackgroundPath() {
        Background background = this.getTopBarBackground();
        return background == null || background.getData() == null ? null : BRANDING_TOP_BAR_BG_BASE_PATH + Objects.hash(background.getUpdatedDate());
    }

    public String getSideBarBackgroundPath() {
        Background background = this.getSideBarBackground();
        return background == null || background.getData() == null ? null : BRANDING_SIDEBAR_BG_BASE_PATH + Objects.hash(background.getUpdatedDate());
    }

    public String getDrawerBackgroundPath() {
        Background background = this.getDrawerBackground();
        return background == null || background.getData() == null ? null : BRANDING_DRAWER_BG_BASE_PATH + Objects.hash(background.getUpdatedDate());
    }

    public void updateLastUpdatedTime(long lastUpdatedTimestamp) {
        if (lastUpdatedTimestamp <= 0L) {
            this.settingService.remove(Context.GLOBAL, Scope.GLOBAL, BRANDING_LAST_UPDATED_TIME_KEY);
        } else {
            this.settingService.set(Context.GLOBAL, Scope.GLOBAL, BRANDING_LAST_UPDATED_TIME_KEY, SettingValue.create((Long)lastUpdatedTimestamp));
        }
        this.themeCSSContent = null;
        this.customCss = null;
    }

    public void updateLogo(Logo logo) {
        this.updateLogo(logo, true);
    }

    public void updateFavicon(Favicon favicon) {
        this.updateFavicon(favicon, true);
    }

    public void updateLoginBackground(Background loginBackground) {
        this.updateLoginBackground(loginBackground, true);
    }

    public void updateThemeStyle(Map<String, String> themeStyle) {
        this.updateThemeStyle(themeStyle, true);
    }

    public Map<String, String> getThemeStyle() {
        if (this.themeVariables == null || this.themeVariables.isEmpty()) {
            return Collections.emptyMap();
        }
        HashMap<String, String> themeStyleVariables = new HashMap<String, String>();
        Set<String> variables = this.themeVariables.keySet();
        for (String themeVariable : variables) {
            SettingValue storedStyleValue = this.settingService.get(BRANDING_CONTEXT, BRANDING_SCOPE, themeVariable);
            String styleValue = storedStyleValue == null || storedStyleValue.getValue() == null ? this.themeVariables.get(themeVariable) : storedStyleValue.getValue().toString();
            if (!StringUtils.isNotBlank((CharSequence)styleValue)) continue;
            themeStyleVariables.put(themeVariable, styleValue);
        }
        return themeStyleVariables;
    }

    public Map<String, String> getDefaultThemeStyle() {
        if (this.themeVariables == null || this.themeVariables.isEmpty()) {
            return Collections.emptyMap();
        }
        return this.themeVariables;
    }

    public Map<String, String> getLoginTitle() {
        if (this.loginTitle == null) {
            HashMap<String, String> valuePerLanguage = new HashMap<String, String>();
            for (String language : this.supportedLanguages.keySet()) {
                SettingValue storedValue = this.settingService.get(BRANDING_CONTEXT, BRANDING_SCOPE, BRANDING_TITLE_SETTING_KEY + language);
                if (storedValue == null || storedValue.getValue() == null) continue;
                valuePerLanguage.put(language, storedValue.getValue().toString());
            }
            valuePerLanguage.computeIfAbsent(this.getDefaultLanguage(), key -> this.defaultLoginTitle);
            this.loginTitle = valuePerLanguage;
        }
        return Collections.unmodifiableMap(this.loginTitle);
    }

    public Map<String, String> getLoginSubtitle() {
        if (this.loginSubtitle == null) {
            HashMap<String, String> valuePerLanguage = new HashMap<String, String>();
            for (String language : this.supportedLanguages.keySet()) {
                SettingValue storedValue = this.settingService.get(BRANDING_CONTEXT, BRANDING_SCOPE, BRANDING_SUBTITLE_SETTING_KEY + language);
                if (storedValue == null || storedValue.getValue() == null) continue;
                valuePerLanguage.put(language, storedValue.getValue().toString());
            }
            valuePerLanguage.computeIfAbsent(this.getDefaultLanguage(), key -> this.defaultLoginSubtitle);
            this.loginSubtitle = valuePerLanguage;
        }
        return Collections.unmodifiableMap(this.loginSubtitle);
    }

    public String getLoginTitle(Locale locale) {
        Map<String, String> valuePerLanguage = this.getLoginTitle();
        if (valuePerLanguage.containsKey(locale.getLanguage())) {
            return valuePerLanguage.get(locale.getLanguage());
        }
        return valuePerLanguage.getOrDefault(this.getDefaultLanguage(), this.defaultLoginTitle);
    }

    public String getLoginSubtitle(Locale locale) {
        Map<String, String> valuePerLanguage = this.getLoginSubtitle();
        if (valuePerLanguage.containsKey(locale.getLanguage())) {
            return valuePerLanguage.get(locale.getLanguage());
        }
        return valuePerLanguage.getOrDefault(this.getDefaultLanguage(), this.defaultLoginTitle);
    }

    public String getDefaultLanguage() {
        return this.getDefaultLocale().toLanguageTag();
    }

    private void loadInitParams(InitParams initParams) {
        if (initParams != null) {
            ValuesParam lessVariablesParam;
            ValueParam lessFileParam;
            ValueParam loginSubtitleParam;
            ValueParam loginTitleParam;
            ValueParam loginBackgroundParam;
            ValueParam favIconParam;
            ValueParam logoParam;
            ValueParam siteNameParam;
            ValueParam companyLinkParam;
            ValueParam companyNameParam = initParams.getValueParam("exo.branding.company.name");
            if (companyNameParam != null) {
                this.defaultCompanyName = companyNameParam.getValue();
            }
            if ((companyLinkParam = initParams.getValueParam("exo.branding.company.link")) != null) {
                this.defaultCompanyLink = companyLinkParam.getValue();
            }
            if ((siteNameParam = initParams.getValueParam("exo.branding.company.siteName")) != null) {
                this.defaultSiteName = siteNameParam.getValue();
            }
            if ((logoParam = initParams.getValueParam(BRANDING_LOGO_INIT_PARAM)) != null) {
                this.defaultConfiguredLogoPath = logoParam.getValue();
            }
            if ((favIconParam = initParams.getValueParam(BRANDING_FAVICON_INIT_PARAM)) != null) {
                this.defaultConfiguredFaviconPath = favIconParam.getValue();
            }
            if ((loginBackgroundParam = initParams.getValueParam("authentication.background")) != null) {
                this.defaultConfiguredLoginBgPath = loginBackgroundParam.getValue();
            }
            if ((loginTitleParam = initParams.getValueParam(BRANDING_LOGIN_TITLE_PARAM)) != null) {
                this.defaultLoginTitle = loginTitleParam.getValue();
            }
            if ((loginSubtitleParam = initParams.getValueParam(BRANDING_LOGIN_SUBTITLE_PARAM)) != null) {
                this.defaultLoginSubtitle = loginSubtitleParam.getValue();
            }
            if ((lessFileParam = initParams.getValueParam(BRANDING_THEME_LESS_PATH)) != null) {
                this.lessFilePath = lessFileParam.getValue();
            }
            if ((lessVariablesParam = initParams.getValuesParam(BRANDING_THEME_VARIABLES)) != null) {
                List<String> variables = lessVariablesParam.getValues();
                this.themeVariables = new HashMap<String, String>();
                for (String themeVariable : variables) {
                    if (StringUtils.isBlank((CharSequence)themeVariable) || !themeVariable.contains(":")) continue;
                    String[] themeVariablesPart = themeVariable.split(":");
                    this.themeVariables.put(themeVariablesPart[0], themeVariablesPart[1]);
                }
            }
        }
    }

    private Map<String, String> loadLanguages() {
        Locale defaultLocale = this.getDefaultLocale();
        this.supportedLanguages = this.localeConfigService.getLocalConfigs() == null ? Collections.singletonMap(defaultLocale.getLanguage(), this.getLocaleDisplayName(defaultLocale, defaultLocale)) : this.localeConfigService.getLocalConfigs().stream().filter(localeConfig -> !StringUtils.equals((CharSequence)localeConfig.getLocaleName(), (CharSequence)"ma")).collect(Collectors.toMap(LocaleConfig::getLocaleName, localeConfig -> this.getLocaleDisplayName(defaultLocale, localeConfig.getLocale())));
        return this.supportedLanguages;
    }

    private Locale getDefaultLocale() {
        return this.localeConfigService.getDefaultLocaleConfig() == null ? Locale.getDefault() : this.localeConfigService.getDefaultLocaleConfig().getLocale();
    }

    private String getDefaultLocaleDirection() {
        LocaleConfig defaultLocaleConfig = this.localeConfigService.getDefaultLocaleConfig();
        return defaultLocaleConfig == null || defaultLocaleConfig.getOrientation() == null || !defaultLocaleConfig.getOrientation().isRT() ? "ltr" : "rtl";
    }

    private String getLocaleDisplayName(Locale defaultLocale, Locale locale) {
        return defaultLocale.equals(locale) ? defaultLocale.getDisplayName(defaultLocale) : locale.getDisplayName(defaultLocale) + " / " + locale.getDisplayName(locale);
    }

    private void updateCompanyLink(String companyLink, boolean updateLastUpdatedTime) {
        this.updatePropertyValue("exo.branding.company.link", companyLink, updateLastUpdatedTime);
    }

    private void updateSiteName(String siteName, boolean updateLastUpdatedTime) {
        this.updatePropertyValue("exo.branding.company.siteName", siteName, updateLastUpdatedTime);
    }

    private void updateLoginBackgroundTextColor(String textColor, boolean updateLastUpdatedTime) {
        this.updatePropertyValue(BRANDING_LOGIN_TEXT_COLOR_KEY, textColor, updateLastUpdatedTime);
    }

    private void updateLoginBackgroundAltText(String altText, boolean updateLastUpdatedTime) {
        this.updatePropertyValue(BRANDING_LOGIN_ALT_TEXT_KEY, altText, updateLastUpdatedTime);
    }

    private void updatePageBackgroundColor(String color, boolean updateLastUpdatedTime) {
        this.updatePropertyValue(BRANDING_PAGE_BG_COLOR_KEY, color, updateLastUpdatedTime);
    }

    private void updatePageBackgroundSize(String value, boolean updateLastUpdatedTime) {
        this.updatePropertyValue(BRANDING_PAGE_BG_SIZE_KEY, value, updateLastUpdatedTime);
    }

    private void updatePageBackgroundPosition(String value, boolean updateLastUpdatedTime) {
        this.updatePropertyValue(BRANDING_PAGE_BG_POSITION_KEY, value, updateLastUpdatedTime);
    }

    private void updatePageBackgroundRepeat(String value, boolean updateLastUpdatedTime) {
        this.updatePropertyValue(BRANDING_PAGE_BG_REPEAT_KEY, value, updateLastUpdatedTime);
    }

    private void updatePageBackgroundEffect(String effect, boolean updateLastUpdatedTime) {
        this.updatePropertyValue(BRANDING_PAGE_BG_EFFECT_KEY, effect, updateLastUpdatedTime);
    }

    private void updatePageWidth(String value, boolean updateLastUpdatedTime) {
        this.updatePropertyValue(BRANDING_PAGE_WIDTH_KEY, value, updateLastUpdatedTime);
    }

    private void updateCustomCss(String value, boolean updateLastUpdatedTime) {
        this.updatePropertyValue(BRANDING_CUSTOM_CSS, value, updateLastUpdatedTime);
    }

    private void updateCompanyName(String companyName, boolean updateLastUpdatedTime) {
        this.updatePropertyValue("exo.branding.company.name", companyName, updateLastUpdatedTime);
    }

    private void updateThemeStyle(Map<String, String> themeStyles, boolean updateLastUpdatedTime) {
        if (this.themeVariables == null || this.themeVariables.isEmpty()) {
            return;
        }
        Set<String> variables = this.themeVariables.keySet();
        for (String themeVariable : variables) {
            if (themeStyles != null && themeStyles.get(themeVariable) != null) {
                String themeStyle = themeStyles.get(themeVariable);
                this.settingService.set(BRANDING_CONTEXT, BRANDING_SCOPE, themeVariable, SettingValue.create((String)themeStyle));
                continue;
            }
            this.settingService.remove(BRANDING_CONTEXT, BRANDING_SCOPE, themeVariable);
        }
        this.triggerBrandingUpdated(updateLastUpdatedTime, updateLastUpdatedTime);
    }

    private void updateLoginTitle(Map<String, String> titles) {
        for (String language : this.supportedLanguages.keySet()) {
            if (titles.containsKey(language)) {
                String value = titles.get(language);
                this.settingService.set(BRANDING_CONTEXT, BRANDING_SCOPE, BRANDING_TITLE_SETTING_KEY + language, SettingValue.create((String)(value == null ? "" : value)));
                continue;
            }
            this.settingService.remove(BRANDING_CONTEXT, BRANDING_SCOPE, BRANDING_TITLE_SETTING_KEY + language);
        }
        this.loginTitle = null;
    }

    private void updateLoginSubtitle(Map<String, String> subtitles) {
        for (String language : this.supportedLanguages.keySet()) {
            if (subtitles.containsKey(language)) {
                String value = subtitles.get(language);
                this.settingService.set(BRANDING_CONTEXT, BRANDING_SCOPE, BRANDING_SUBTITLE_SETTING_KEY + language, SettingValue.create((String)(value == null ? "" : value)));
                continue;
            }
            this.settingService.remove(BRANDING_CONTEXT, BRANDING_SCOPE, BRANDING_SUBTITLE_SETTING_KEY + language);
        }
        this.loginSubtitle = null;
    }

    private void updateLogo(Logo logo, boolean updateLastUpdatedTime) {
        this.updateBrandingFile((BrandingFile)logo, LOGO_NAME, this.getLogoId(), BRANDING_LOGO_ID_SETTING_KEY);
        this.logo = null;
        this.triggerBrandingUpdated(updateLastUpdatedTime, updateLastUpdatedTime);
    }

    private void updateFavicon(Favicon favicon, boolean updateLastUpdatedTime) {
        this.updateBrandingFile((BrandingFile)favicon, FAVICON_NAME, this.getFaviconId(), BRANDING_FAVICON_ID_SETTING_KEY);
        this.favicon = null;
        this.triggerBrandingUpdated(updateLastUpdatedTime, updateLastUpdatedTime);
    }

    private void updateLoginBackground(Background loginBackground, boolean updateLastUpdatedTime) {
        this.updateBrandingFile((BrandingFile)loginBackground, LOGIN_BACKGROUND_NAME, this.getLoginBackgroundId(), "authentication.background");
        this.loginBackground = null;
        this.triggerBrandingUpdated(updateLastUpdatedTime, updateLastUpdatedTime);
    }

    private void updateTopBarBackground(Background topBarBackground, boolean updateLastUpdatedTime) {
        this.updateBrandingFile((BrandingFile)topBarBackground, TOP_BAR_BACKGROUND_NAME, this.getTopBarBackgroundId(), BRANDING_TOP_BAR_BG_ID_SETTING_KEY);
        this.topBarBackground = null;
        this.triggerBrandingUpdated(updateLastUpdatedTime, updateLastUpdatedTime);
    }

    private void updateSideBarBackground(Background sideBarBackground, boolean updateLastUpdatedTime) {
        this.updateBrandingFile((BrandingFile)sideBarBackground, SIDEBAR_BACKGROUND_NAME, this.getSideBarBackgroundId(), BRANDING_SIDEBAR_BG_ID_SETTING_KEY);
        this.sideBarBackground = null;
        this.triggerBrandingUpdated(updateLastUpdatedTime, updateLastUpdatedTime);
    }

    private void updateDrawerBackground(Background drawerBackground, boolean updateLastUpdatedTime) {
        this.updateBrandingFile((BrandingFile)drawerBackground, DRAWER_BACKGROUND_NAME, this.getDrawerBackgroundId(), BRANDING_DRAWER_BG_ID_SETTING_KEY);
        this.drawerBackground = null;
        this.triggerBrandingUpdated(updateLastUpdatedTime, updateLastUpdatedTime);
    }

    private void updatePageBackground(Background background, boolean updateLastUpdatedTime) {
        this.updateBrandingFile((BrandingFile)background, PAGE_BACKGROUND_NAME, this.getPageBackgroundId(), BRANDING_PAGE_BG_ID_SETTING_KEY);
        this.pageBackground = null;
        this.triggerBrandingUpdated(updateLastUpdatedTime, updateLastUpdatedTime);
    }

    private void updateBrandingFile(BrandingFile brandingFile, String fileName, Long fileId, String settingKey) {
        String uploadId = brandingFile == null ? null : brandingFile.getUploadId();
        this.updateBrandingFile(uploadId, fileName, fileId, settingKey);
        if (fileId != null && StringUtils.isNotBlank((CharSequence)uploadId) && !StringUtils.equals((CharSequence)BRANDING_RESET_ATTACHMENT_ID, (CharSequence)uploadId)) {
            this.fileService.deleteFile(fileId.longValue());
        }
    }

    private void updateBrandingFile(String uploadId, String fileName, Long fileId, String settingKey) {
        try {
            if (StringUtils.equals((CharSequence)BRANDING_RESET_ATTACHMENT_ID, (CharSequence)uploadId)) {
                this.removeBrandingFile(fileId, settingKey);
            } else if (StringUtils.isNotBlank((CharSequence)uploadId)) {
                this.updateBrandingFileByUploadId(uploadId, fileName, settingKey);
            }
        }
        catch (Exception e) {
            throw new IllegalStateException("Error while updating login background", e);
        }
    }

    private void removeBrandingFile(Long fileId, String settingKey) {
        if (fileId != null && fileId > 0L) {
            this.fileService.deleteFile(fileId.longValue());
            this.settingService.remove(Context.GLOBAL, Scope.GLOBAL, settingKey);
        }
    }

    private void updateBrandingFileByUploadId(String uploadId, String fileName, String settingKey) throws Exception {
        InputStream inputStream = this.getUploadDataAsStream(uploadId);
        if (inputStream == null) {
            throw new IllegalArgumentException("Cannot update " + fileName + ", the object must contain the image data or an upload id");
        }
        this.updateBrandingFileByInputStream(inputStream, fileName, settingKey);
    }

    private FileItem updateBrandingFileByInputStream(InputStream inputStream, String fileName, String settingKey) throws Exception {
        int size = inputStream.available();
        FileItem fileItem = new FileItem(Long.valueOf(0L), fileName, "image/png", FILE_API_NAME_SPACE, (long)size, new Date(), this.userAcl.getSuperUser(), false, inputStream);
        fileItem = this.fileService.writeFile(fileItem);
        this.settingService.set(Context.GLOBAL, Scope.GLOBAL, settingKey, SettingValue.create((String)String.valueOf(fileItem.getFileInfo().getId())));
        return fileItem;
    }

    private String computeThemeCSS() {
        if (StringUtils.isNotBlank((CharSequence)this.lessFilePath)) {
            try {
                InputStream inputStream = this.configurationManager.getInputStream(this.lessFilePath);
                this.lessThemeContent = IOUtil.getStreamContentAsString((InputStream)inputStream);
            }
            catch (Exception e) {
                LOG.warn((Object)"Error retrieving less file content", (Throwable)e);
            }
        }
        if (this.themeVariables != null && !this.themeVariables.isEmpty()) {
            Set<String> variables = this.themeVariables.keySet();
            for (String themeVariable : variables) {
                SettingValue storedColorValue = this.settingService.get(BRANDING_CONTEXT, BRANDING_SCOPE, themeVariable);
                String colorValue = storedColorValue == null || storedColorValue.getValue() == null ? this.themeVariables.get(themeVariable) : storedColorValue.getValue().toString();
                if (!StringUtils.isNotBlank((CharSequence)colorValue) || !StringUtils.isNotBlank((CharSequence)this.lessThemeContent)) continue;
                this.lessThemeContent = this.lessThemeContent.replaceAll("@" + themeVariable + ":[ #a-zA-Z0-9]*;?\r?\n", "@" + themeVariable + ": " + colorValue + ";\n");
            }
            if (StringUtils.isNotBlank((CharSequence)this.lessThemeContent)) {
                ThreadUnsafeLessCompiler compiler = new ThreadUnsafeLessCompiler();
                try {
                    LessCompiler.Configuration configuration = new LessCompiler.Configuration();
                    configuration.getSourceMapConfiguration().setLinkSourceMap(false);
                    LessCompiler.CompilationResult result = compiler.compile(this.lessThemeContent, configuration);
                    this.themeCSSContent = result.getCss();
                }
                catch (Less4jException e) {
                    LOG.warn((Object)"Error compiling less file content", (Throwable)e);
                }
            }
        }
        if (StringUtils.isNotBlank((CharSequence)this.getCustomCssContent()) && this.getFeatureService() != null && this.getFeatureService().isActiveFeature(BRANDING_CUSTOM_STYLE_FEATURE)) {
            this.themeCSSContent = this.themeCSSContent + "\n" + this.customCss;
        }
        return this.themeCSSContent;
    }

    private String getCustomCssContent() {
        if (this.customCss == null) {
            this.customCss = this.getCustomCss();
            if (this.customCss == null) {
                this.customCss = "";
            }
        }
        return this.customCss;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private InputStream getUploadDataAsStream(String uploadId) throws FileNotFoundException {
        UploadResource uploadResource = this.uploadService.getUploadResource(uploadId);
        if (uploadResource == null) {
            return null;
        }
        try {
            FileInputStream fileInputStream = new FileInputStream(new File(uploadResource.getStoreLocation()));
            return fileInputStream;
        }
        finally {
            this.uploadService.removeUploadResource(uploadId);
        }
    }

    private <T extends BrandingFile> T retrieveStoredBrandingFile(long imageId, T brandingFile) throws FileStorageException {
        FileItem fileItem = this.fileService.getFile(imageId);
        if (fileItem != null) {
            brandingFile.setData(fileItem.getAsByte());
            brandingFile.setSize(fileItem.getFileInfo().getSize());
            brandingFile.setUpdatedDate(fileItem.getFileInfo().getUpdatedDate().getTime());
            brandingFile.setFileId(imageId);
        }
        return brandingFile;
    }

    private <T extends BrandingFile> T retrieveDefaultBrandingFile(String imagePath, T brandingFile, String fileName, String settingKey) throws Exception {
        if (StringUtils.isNotBlank((CharSequence)imagePath)) {
            byte[] bytes = null;
            long lastModified = DEFAULT_LAST_MODIFED;
            File file = new File(imagePath);
            if (file.exists()) {
                bytes = Files.readAllBytes(file.toPath());
                lastModified = file.lastModified();
            } else {
                try (InputStream is = this.container.getPortalContext().getResourceAsStream(imagePath);){
                    if (is != null) {
                        bytes = IOUtil.getStreamContentAsBytes((InputStream)is);
                    }
                }
            }
            if (bytes != null) {
                FileItem fileItem = this.updateBrandingFileByInputStream(new ByteArrayInputStream(bytes), fileName, settingKey);
                brandingFile.setFileId(fileItem.getFileInfo().getId().longValue());
                brandingFile.setData(bytes);
                brandingFile.setSize((long)bytes.length);
                brandingFile.setUpdatedDate(lastModified);
            }
        }
        return brandingFile;
    }

    private String getPropertyValue(String key) {
        return this.getPropertyValue(key, null);
    }

    private Long getPropertyValueLong(String key) {
        String value = this.getPropertyValue(key, null);
        return StringUtils.isBlank((CharSequence)value) ? null : Long.valueOf(Long.parseLong(value));
    }

    private String getPropertyValue(String key, String defaultValue) {
        SettingValue value = this.settingService.get(Context.GLOBAL, Scope.GLOBAL, key);
        if (value != null && value.getValue() != null && StringUtils.isNotBlank((CharSequence)value.getValue().toString())) {
            return value.getValue().toString();
        }
        return defaultValue;
    }

    private void updatePropertyValue(String key, String value, boolean updateLastUpdatedTime) {
        if (StringUtils.isBlank((CharSequence)value)) {
            this.settingService.remove(Context.GLOBAL, Scope.GLOBAL, key);
        } else {
            this.settingService.set(Context.GLOBAL, Scope.GLOBAL, key, SettingValue.create((String)value));
        }
        this.triggerBrandingUpdated(updateLastUpdatedTime, updateLastUpdatedTime);
    }

    private void validateCSSInputs(Branding branding) {
        Arrays.asList(branding.getCustomCss(), branding.getPageBackgroundColor(), branding.getPageBackgroundPosition(), branding.getPageBackgroundRepeat(), branding.getPageBackgroundSize(), branding.getLoginBackgroundTextColor(), branding.getPageBackgroundColor(), branding.getPageBackgroundColor(), branding.getPageBackgroundColor(), branding.getPageBackgroundColor(), branding.getPageBackgroundColor()).forEach(this::validateCSSStyleValue);
        branding.getThemeStyle().values().forEach(this::validateCSSStyleValue);
    }

    private void validateCSSStyleValue(String value) {
        if (StringUtils.isNotBlank((CharSequence)value) && (value.contains("javascript") || value.contains("eval"))) {
            throw new IllegalArgumentException(String.format("Invalid css value input %s", value));
        }
    }

    private void triggerBrandingUpdated(boolean updateLastUpdatedTime, boolean triggerEvent) {
        if (updateLastUpdatedTime) {
            this.updateLastUpdatedTime(System.currentTimeMillis());
        }
        if (triggerEvent) {
            this.listenerService.broadcast("branding.updated", null, (Object)this.getBrandingInformation(false));
        }
    }

    private void processThemeBackgroundImages(Map<String, String> themeStyles) {
        this.processBackgroundImage(themeStyles, TOP_BAR_BG_IMAGE_THEME_STYLE_KEY, this.getTopBarBackgroundPath());
        this.processBackgroundImage(themeStyles, SIDEBAR_BG_IMAGE_THEME_STYLE_KEY, this.getSideBarBackgroundPath());
        this.processBackgroundImage(themeStyles, DRAWER_BG_IMAGE_THEME_STYLE_KEY, this.getDrawerBackgroundPath());
    }

    private void processBackgroundImage(Map<String, String> themeStyles, String styleKey, String imagePath) {
        if (StringUtils.isNotBlank((CharSequence)imagePath)) {
            String themeStyleValue = themeStyles.get(styleKey);
            StringBuilder backgroundImageValue = new StringBuilder("url(").append(imagePath).append(")");
            if (StringUtils.isNotBlank((CharSequence)themeStyleValue) && !"none".equals(themeStyleValue)) {
                backgroundImageValue.append(", ").append(themeStyleValue);
            }
            themeStyles.put(styleKey, backgroundImageValue.toString());
        }
    }

    private ExoFeatureService getFeatureService() {
        if (this.featureService == null) {
            this.featureService = (ExoFeatureService)this.container.getComponentInstanceOfType(ExoFeatureService.class);
        }
        return this.featureService;
    }
}
