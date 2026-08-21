/**
 * External visio provider module for Web Conferencing. This script will be used to add a
 * provider to Web Conferencing module and then handle calls for portal
 * user/groups.
 */
(function($, webConferencing) {
  "use strict";
  var globalWebConferencing = typeof eXo != "undefined" && eXo && eXo.webConferencing ? eXo.webConferencing : null;
  // Use webConferencing from global eXo namespace (for non AMD uses).
  // This can be actual when running the script outside the portal page - e.g.
  // on a custom call page.
  if (!webConferencing && globalWebConferencing) {
    webConferencing = globalWebConferencing;
  }

  if (webConferencing) {

    // Start with default logger, later in configure() we'll get it for the
    // provider.
    // We know it's jitsi here.
    var log = webConferencing.getLog("externalvisio");

    /**
     * An object that implements Web Conferencing SPI contract for a call
     * provider.
     */
    function ExternalVisioProvider() {

      var self = this;
      var settings;

      /**
       * Init Jitsi provider, it will be called by Web Conferencing
       * core on addProvider() method. It is assumed that the connector will
       * initialize internals depending on the given context.
       */
      this.init = function(context) {
        var process = $.Deferred();
        process.resolve();
        return process.promise();
      };

      /**
       * Set connector settings from the server-side. Will be called by script
       * of JitsiPortlet class.
       */
      this.configure = function(newSettings) {
        settings = newSettings;
      };

      this.linkSupported = true;

      /**
       * Jitsi supports group calls.
       */
      this.groupSupported = true;

      /**
       * With External Visio, we allow to modify event url
       */
      this.canModifyEventUrl = true;


      /**
       * MUST return a call type name. If several types supported, this one is
       * assumed as major one and it will be used for referring this connector
       * in getProvider() and similar methods. This type also should listed in
       * getSupportedTypes(). Call type is the same as used in user profile.
       */
      this.getType = function() {
        return 'externalVisio';
//        if (settings) {
//          return settings.type;
//        }
      };

      /**
      * Must return if the current provider support invited users
      */
      this.supportInvitedUsers = function() {
        return false;
      };

      /**
       * MUST return all call types supported by a connector.
       */
      this.getSupportedTypes = function() {
        return ['externalVisio'];
//        if (settings) {
//          return settings.supportedTypes;
//        }
      };

      /**
       * MUST return human-readable title of a connector.
       */
      this.getTitle = function() {
        return 'ExternalVisio';
      };

      /*
       * PATCH PMO/Olimpia (2026-08-20) -- ver AUDIT [116].
       * Defeito de produto: o eXo so' mostra o botao de videoconferencia se
       * o proprio usuario (ou o gestor do espaco) tiver digitado A MAO um
       * link de videoconferencia no perfil. Numa instalacao nova ninguem
       * digitou nada, entao GET /v1/externalVisio/<identityId> devolve []
       * e o botao NUNCA aparece -- foi exatamente o que o operador reportou.
       * Correcao: quando nao ha' link gravado, gera-se uma sala determinista
       * no Jitsi auto-hospedado desta mesma stack, uma por identidade
       * (usuario ou espaco). Continua valendo o link manual quando existir:
       * o fallback so' entra quando a resposta vem vazia.
       */
      var visioFallbackBase = function() {
        if (typeof window !== 'undefined' && window.EXO_VISIO_FALLBACK_BASE) {
          return String(window.EXO_VISIO_FALLBACK_BASE).replace(/\/+$/, '');
        }
        return 'https://' + window.location.hostname + ':8443';
      };

      var getActiveProviders = function(identityId, isSpace) {
        return fetch(`${eXo.env.portal.context}/${eXo.env.portal.rest}/v1/externalVisio/${identityId}`, {
          credentials: 'include',
          method: 'GET'
        }).then(resp => {
          if (resp.ok) {
            return resp.json();
          } else {
            throw new Error('Error when retrieving active providers');
          }
        }).then(providers => {
          if (providers && providers.length) {
            return providers;
          }
          return fetch(`${eXo.env.portal.context}/${eXo.env.portal.rest}/v1/externalVisio/connectors?enabled=true`, {
            credentials: 'include',
            method: 'GET'
          }).then(r => (r.ok ? r.json() : [])).then(connectors => {
            const base = visioFallbackBase();
            return (connectors || [])
              .filter(c => (isSpace ? c.activeForSpaces : c.activeForUsers) !== false)
              .map((c, i) => ({
                id: c.id,
                name: c.name,
                order: (c.order === null || c.order === undefined) ? i : c.order,
                url: base + '/sala-' + identityId
              }));
          }).catch(() => []);
        });
      }
      // -----------------------------------------------------------------
      // SINALIZACAO DE CHAMADA (2026-08-21)
      // O External Visio nativo faz APENAS window.open(url): quem clica entra
      // sozinho na sala e o outro lado NUNCA fica sabendo -- nao chama, nao
      // alerta, nao toca. Pior: a URL vem do perfil do DESTINATARIO, entao A
      // abre 'pmeto-B' e B abre 'pmeto-A' -- salas DIFERENTES; mesmo os dois
      // clicando, nunca se encontram.
      // Aqui: (a) a sala passa a ser derivada do roomId da conversa Matrix,
      // que e' o mesmo para os dois lados -- sala SIMETRICA; e (b) antes de
      // abrir, e' postada uma mensagem no proprio chat com o link, que dispara
      // a notificacao nativa do eXo no destinatario. O link recebido leva a
      // MESMA sala de quem ligou.
      // Degrada com seguranca: qualquer falha cai na URL original.
      // -----------------------------------------------------------------
      var visioCallRoomUrl = function(roomId) {
        return visioFallbackBase() + '/exo-' + String(roomId).replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
      };

      var visioSignalCall = function(dmMemberId) {
        try {
          var ms = (window.Vue && Vue.prototype) ? Vue.prototype.$matrixService : null;
          if (!ms || !ms.retrieveCachedRooms || !dmMemberId) {
            return Promise.resolve(null);
          }
          return Promise.resolve(ms.retrieveCachedRooms()).then(function(raw) {
            var rooms = (typeof raw === 'string') ? JSON.parse(raw) : raw;
            var room = (rooms || []).filter(function(r) { return r && r.dmMemberId === dmMemberId; })[0];
            if (!room || !room.id) {
              return null;
            }
            var url = visioCallRoomUrl(room.id);
            var tok = window.localStorage.getItem('matrix_access_token');
            if (!tok) {
              return url;
            }
            var txn = 'visio' + Date.now();
            var texto = 'Chamada de video iniciada. Entre em: ' + url;
            return fetch('/_matrix/client/v3/rooms/' + encodeURIComponent(room.id)
                         + '/send/m.room.message/' + txn, {
              method: 'PUT',
              headers: { 'Authorization': 'Bearer ' + tok, 'Content-Type': 'application/json' },
              body: JSON.stringify({ msgtype: 'm.text', body: texto })
            }).then(function() { return url; }).catch(function() { return url; });
          }).catch(function() { return null; });
        } catch (e) {
          return Promise.resolve(null);
        }
      };

      var startCall = function(url) {
        if (!url.match(/^(https?:\/\/|\/portal\/)/)) {
          url = `//${url}`;
        }
        window.open(url, '_blank');
      }

      this.callButton = function(context, buttonType) {
        var button = $.Deferred();
        if (context && context.currentUser) {
          context.details().then(target => {
            if (!buttonType || buttonType === "vue") {
              let activeButtons = [];
              if (context.isSpace || context.isUser) {
              const identityId = context.isSpace ? context.spaceId : context.userId;
                getActiveProviders(identityId, context.isSpace)
                .then((activeProviders) => {
                  activeButtons = activeProviders;
                  const buttonComponents = []; // Créer une liste pour stocker les composants Vue
                  activeButtons.forEach(p => {
                    const callSettings = {};
                    callSettings.target = target;
                    callSettings.context = context;
                    callSettings.provider = self;
                    callSettings.nameConnector = p.name;
                    callSettings.urlConnector = p.url;
                    callSettings.order = p.order;
                    callSettings.onCallOpen = () => {
                      // sinaliza no chat e usa a sala simetrica; se falhar, mantem a URL original
                      const dmId = context.isSpace ? null : identityId;
                      Promise.resolve(visioSignalCall(dmId)).then((salaSimetrica) => {
                        startCall(salaSimetrica || callSettings.urlConnector);
                      }).catch(() => startCall(callSettings.urlConnector));
                    };
                    callButton.init(callSettings).then(comp => {
                      // Ajouter le composant Vue à la liste
                      buttonComponents.push(comp);

                      if (buttonComponents.length === activeButtons.length) {
                        buttonComponents.sort((button1, button2) => {
                          return (button1.callSettings.order - button2.callSettings.order);
                        });
                        button.resolve(buttonComponents);
                      }
                    });
                  });
                });
              } else {
                button.resolve(activeButtons);
              }
            } else {
              const message = "Button type not supported: " + buttonType;
              log.error(message);
              button.reject(message);
            }
          }).catch(err => {
            // Gérer les erreurs
            if (err && err.code == "NOT_FOUND_ERROR") {
              button.reject(err.message);
            } else {
              var msg = "Error getting context details";
              log.error(msg, err);
              button.reject(msg, err);
            }
          });
        } else {
          var msg = "Not configured or empty context";
          log.error(msg);
          button.reject(msg);
        }
        return button.promise();
      };

      this.getCallId = function(context) {
        var process = $.Deferred();
        if (context.isUser) {
          process.resolve(context.currentUser.id);
        } else {
          Vue.prototype.$identityService.getIdentityById(context.spaceId)
            .then((identity) => process.resolve(identity.remoteId));
        }
        return process.promise();
      };

      var getCallUrl = function(callId, isSpace) {
        var process = $.Deferred();
        getActiveProviders(callId, isSpace)
          .then((activeProviders) => {
            if(activeProviders.length>0) {
              process.resolve(activeProviders[0].url);
            }
          });
        return process.promise();
      };
      this.getCallUrl = getCallUrl;

    };

    var provider = new ExternalVisioProvider();

    // Add ExternalVisio provider into webConferencing object of global eXo namespace
    // (for non AMD uses)
    if (globalWebConferencing) {
      globalWebConferencing.externalvisio = provider;
    } else {
      log.warn("eXo.webConferencing not defined");
    }

    log.trace("< Loaded at " + location.origin + location.pathname);
    return provider;
  } else {
    window.console &&
      window.console
        .log("WARN: webConferencing not given and eXo.webConferencing not defined. ExternalVisio provider registration skipped.");
  }
})($, webConferencing, callButton);
