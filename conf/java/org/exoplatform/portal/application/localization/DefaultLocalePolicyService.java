/*
 * PATCH 2026-08-19: descompilado com CFR 0.152 a partir de
 * portal.webui.portal.jar (imagem oficial eXo/Meeds 7.2.1). Registrado como
 * ÚNICA implementação de LocalePolicy em
 * portal.war/WEB-INF/conf/portal/web-configuration.xml (key
 * org.exoplatform.services.resources.LocalePolicy) -- não há outro binding
 * a considerar.
 *
 * MUDANCA DE COMPORTAMENTO, A PEDIDO EXPLICITO DO OPERADOR (nao e' correcao
 * de bug -- e' redesign de precedencia): na ordem original,
 * determineLocale() so' usava o "Idioma padrao" (LocaleConfigService.
 * getDefaultLocaleConfig()) como ULTIMO fallback, depois de: (1) URL
 * explicita (/portal/<lang>/...), (2) idioma pessoal salvo no perfil do
 * usuario logado, (3) cookie, (4) sessao, (5) header Accept-Language do
 * navegador. Na pratica isso deixava o "Idioma padrao" da Administracao
 * sem efeito visivel: qualquer conta com idioma pessoal salvo, e qualquer
 * navegador real (que sempre manda Accept-Language), nunca chegava a ver
 * o (5)/(6) — o fallback do fallback.
 *
 * Pedido explicito: "ao mudar o idioma, o idioma deve ser alterado" -- ou
 * seja, o "Idioma padrao" da Administracao deve valer para TODO MUNDO,
 * logado ou nao, e sobrepor tanto o perfil pessoal salvo quanto o
 * Accept-Language do navegador. A UNICA excecao mantida e' a navegacao
 * explicita por URL (clicar num link /portal/<lang>/...), que continua
 * funcionando -- e' uma acao deliberada da pessoa, nao uma preferencia
 * passiva armazenada, e e' o mesmo mecanismo usado para corrigir a conta
 * 'root' presa em vietnamita mais cedo nesta mesma sessao de trabalho.
 * Os metodos getLocaleConfigFor... e getLocaleConfigFrom... abaixo ficam sem uso
 * em determineLocale() mas NAO foram removidos: NoBrowserLocalePolicyService
 * (mesmo pacote) estende esta classe e sobrescreve um deles -- removê-los
 * quebraria aquela subclasse, mesmo que ela não esteja registrada/ativa
 * hoje.
 *
 * ---- cabecalho original do CFR, abaixo ----
 *
 * Could not load the following classes:
 *  org.apache.commons.lang3.StringUtils
 *  org.exoplatform.container.xml.InitParams
 *  org.exoplatform.services.resources.LocaleConfig
 *  org.exoplatform.services.resources.LocaleConfigService
 *  org.exoplatform.services.resources.LocaleContextInfo
 *  org.exoplatform.services.resources.LocalePolicy
 */
package org.exoplatform.portal.application.localization;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.resources.LocaleConfig;
import org.exoplatform.services.resources.LocaleConfigService;
import org.exoplatform.services.resources.LocaleContextInfo;
import org.exoplatform.services.resources.LocalePolicy;

public class DefaultLocalePolicyService
implements LocalePolicy {
    private static final String USE_DEFAULT_SITE_LANGUAGE_PARAM = "useDefaultSiteLanguage";
    private LocaleConfigService localeConfigService;
    private boolean useDefaultSiteLanguage;

    public DefaultLocalePolicyService(LocaleConfigService localeConfigService, InitParams params) {
        this.localeConfigService = localeConfigService;
        if (params != null && params.containsKey((Object)USE_DEFAULT_SITE_LANGUAGE_PARAM)) {
            this.useDefaultSiteLanguage = Boolean.parseBoolean(params.getValueParam(USE_DEFAULT_SITE_LANGUAGE_PARAM).getValue());
        }
    }

    public Locale determineLocale(LocaleContextInfo context) {
        if (context.getRequestLocale() != null) {
            return context.getRequestLocale();
        }
        return this.localeConfigService.getDefaultLocaleConfig().getLocale();
    }

    protected Locale getLocaleConfigForRegistered(LocaleContextInfo context) {
        Locale locale = context.getLocaleIfLangSupported(context.getUserProfileLocale());
        if (locale == null) {
            locale = this.getLocaleConfigFromCookie(context);
        }
        if (locale == null) {
            locale = this.getLocaleConfigFromSession(context);
        }
        if (locale == null) {
            locale = this.getLocaleConfigFromBrowser(context);
        }
        return locale;
    }

    protected Locale getLocaleConfigFromBrowser(LocaleContextInfo context) {
        List locales = context.getBrowserLocales();
        if (locales != null && !locales.isEmpty()) {
            return context.getLocaleIfLangSupported((Locale)locales.get(0));
        }
        return null;
    }

    protected Locale getLocaleConfigForAnonymous(LocaleContextInfo context) {
        Locale locale = this.getLocaleConfigFromCookie(context);
        if (locale == null) {
            locale = this.getLocaleConfigFromSession(context);
        }
        if (locale == null) {
            locale = this.getLocaleConfigFromBrowser(context);
        }
        return locale;
    }

    protected Locale getLocaleConfigFromSession(LocaleContextInfo context) {
        return context.getSessionLocale();
    }

    protected Locale getLocaleConfigFromCookie(LocaleContextInfo context) {
        List locales = context.getCookieLocales();
        if (locales != null && !locales.isEmpty()) {
            return context.getLocaleIfLangSupported((Locale)locales.get(0));
        }
        return null;
    }
}
