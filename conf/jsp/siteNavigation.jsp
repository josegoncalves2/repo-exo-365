<%@page import="org.exoplatform.portal.application.PortalRequestContext"%>
<%@page import="io.meeds.layout.service.LayoutAclService"%>
<%@page import="org.exoplatform.container.ExoContainerContext"%>
<%@page import="org.exoplatform.portal.webui.util.Util"%>
<%@page import="org.exoplatform.portal.mop.SiteKey"%>
<%@page import="org.exoplatform.services.resources.ResourceBundleService"%>
<%@page import="java.util.ResourceBundle"%>
<%
  SiteKey siteKey = Util.getUIPortal().getSiteKey();
  LayoutAclService aclService = ExoContainerContext.getService(LayoutAclService.class);
  boolean isAdministrator = aclService.isAdministrator(request.getRemoteUser());
  boolean canManageSiteNavigation = aclService.canEditNavigation(siteKey, request.getRemoteUser());
  if (canManageSiteNavigation) {
    PortalRequestContext rcontext = PortalRequestContext.getCurrentInstance();
    ResourceBundle siteNavigationBundle = ExoContainerContext.getService(ResourceBundleService.class)
        .getResourceBundle("locale.portlet.SiteNavigation", request.getLocale());
    String siteNavigationTooltip = siteNavigationBundle.getString("siteNavigation.button.tooltip.label");
%>
<div class="VuetifyApp">
  <div data-app="true"
    class="v-application v-application--is-ltr theme--light"
    id="siteNavigation">
    <div class="v-application--wrap d-none d-sm-inline">
      <button
        id="topBarSiteNavigation"
        type="button"
        role="button"
        class="ms-5 v-btn v-btn--flat v-btn--icon v-btn--round theme--light v-size--default"
        title="<%=siteNavigationTooltip%>"
        onclick="Vue.startApp('PORTLET/layout/SiteNavigation', 'init', <%=canManageSiteNavigation%>)">
        <span class="v-btn__content">
          <i aria-hidden="true" class="v-icon notranslate fa fa-project-diagram theme--light" style="font-size: 20px;"></i>
        </span>
      </button>
    </div>
  </div>
  <div id="siteNavigation">
    <script type="text/javascript">
      eXo.env.portal.isAdministrator = <%=isAdministrator%>;
      eXo.env.portal.siteLabel = '<%=rcontext.getSiteLabel() == null ? "" : rcontext.getSiteLabel()%>';
    </script>
  </div>
</div>
<% } %>
