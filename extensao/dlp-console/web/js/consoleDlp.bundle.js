/*
 * Console de DLP — aplicacao Vue do portlet `consoleDlp`.
 *
 * PADRAO DA PLATAFORMA, E NAO INVENCAO. A forma deste arquivo (define anonimo,
 * componentes globais via Vue.component, ponte extensionRegistry, Vuetify a
 * partir de eXo.env.portal.vuetifyPreset, i18n por exoi18n.loadLanguageAsync e
 * montagem por Vue.createApp) e' a mesma do add-on nativo em
 * /opt/exo/webapps/dlp/js/dlpQuarantine.bundle.js, lida no container.
 *
 * ESCRITO A MAO, DE PROPOSITO. Nao ha' npm neste host (node v18 sem npm), logo
 * nao ha' empacotador. Como Vue 2 e Vuetify 2.3.10 sao servidos PELA
 * PLATAFORMA e declarados em `depends`, o modulo AMD nao precisa empacotar
 * nada: precisa apenas usar os globais que o carregador ja' colocou de pe'. O
 * arquivo e' servido com <minify>false</minify>, entao o que se le' aqui e' o
 * que corre no navegador.
 *
 * NAO HA' CSS DE LAYOUT AQUI NEM EM LUGAR NENHUM DESTE WAR. Cor, tipografia,
 * espacamento, tabela, aba, cartao e botao vem do skin `Enterprise`, herdado
 * por `portlet-skin` no gatein-resources.xml. Era exatamente o que faltava na
 * versao anterior, que trazia 80 linhas de <style> com paleta propria.
 */
define(function () {
  'use strict';

  var ID = 'consoleDlp';

  // ==========================================================================
  // Ponte REST
  //
  // O navegador NUNCA fala com o servico de DLP: fala com ConsoleDlpRest, que
  // vive no WAR dlp-saida, guarda o token no servidor e exige participacao em
  // /platform/administrators por @RolesAllowed. O servico de DLP nao tem porta
  // publicada. `credentials: include` e' o que leva o cookie de sessao do
  // portal, e e' a mesma escolha do dlpQuarantine nativo.
  // ==========================================================================
  function raiz() {
    return eXo.env.portal.context + '/' + eXo.env.portal.rest + '/dlp-pmo';
  }

  function nativo() {
    return eXo.env.portal.context + '/' + eXo.env.portal.rest + '/dlp';
  }

  function ler(url) {
    return fetch(url, { method: 'GET', credentials: 'include' })
      .then(function (r) {
        if (!r.ok) {
          throw new Error('HTTP ' + r.status);
        }
        return r.json();
      });
  }

  function enviar(metodo, caminho, corpo) {
    return fetch(raiz() + caminho, {
      method: metodo,
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: corpo === undefined ? undefined : JSON.stringify(corpo)
    }).then(function (r) {
      if (!r.ok) {
        throw new Error('HTTP ' + r.status);
      }
      return r.status === 204 ? {} : r.json();
    });
  }

  // Uma lista pode vir como array puro ou embrulhada em {itens:[...]}.
  // Normalizar aqui evita que cada secao repita a mesma defesa.
  function lista(dados, campo) {
    if (Array.isArray(dados)) {
      return dados;
    }
    if (dados && Array.isArray(dados[campo])) {
      return dados[campo];
    }
    if (dados && Array.isArray(dados.itens)) {
      return dados.itens;
    }
    return [];
  }


  // O servico devolve algumas colecoes como MAPA, e nao como lista: os
  // dicionarios vem em `cadastrados` indexados pelo nome, e os resumos de
  // quarentena e de avisos vem indexados pelo estado. Converter aqui evita que
  // cada secao invente a propria travessia.
  function deMapa(objeto, chaveDoNome) {
    if (!objeto || typeof objeto !== 'object' || Array.isArray(objeto)) {
      return [];
    }
    return Object.keys(objeto).map(function (k) {
      var v = objeto[k];
      var linha = (v && typeof v === 'object' && !Array.isArray(v)) ? Object.assign({}, v) : { valor: v };
      linha[chaveDoNome || 'nome'] = k;
      return linha;
    });
  }

  function inteiro(objeto, campo) {
    var v = objeto && objeto[campo];
    return typeof v === 'number' ? v : (parseInt(v, 10) || 0);
  }

  function quando(v) {
    if (!v) {
      return '';
    }
    var d = new Date(typeof v === 'number' ? v * 1000 : v);
    return isNaN(d.getTime()) ? String(v) : d.toLocaleString();
  }

  // ==========================================================================
  // Comportamento comum a toda secao
  //
  // Carregar, mostrar que esta' carregando, e — o ponto que importa — mostrar
  // a FALHA em vez de uma tabela vazia. Uma secao vazia porque o servico caiu e
  // uma secao vazia porque nao ha' dado sao coisas diferentes, e o operador
  // precisa distinguir as duas sem abrir log.
  // ==========================================================================
  var secao = {
    data: function () {
      return { carregando: false, erro: '', dados: null };
    },
    created: function () {
      this.recarregar();
    },
    methods: {
      recarregar: function () {
        var self = this;
        self.carregando = true;
        self.erro = '';
        return Promise.all(self.fontes().map(function (u) {
          return ler(u.indexOf('http') === 0 ? u : raiz() + u);
        })).then(function (respostas) {
          self.dados = respostas.length === 1 ? respostas[0] : respostas;
        }).catch(function (e) {
          self.erro = String(e.message || e);
        }).finally(function () {
          self.carregando = false;
        });
      },
      agir: function (metodo, caminho, corpo, mensagem) {
        var self = this;
        return enviar(metodo, caminho, corpo).then(function () {
          self.$root.$emit('dlp-aviso', mensagem, 'success');
          return self.recarregar();
        }).catch(function (e) {
          self.$root.$emit('dlp-aviso', String(e.message || e), 'error');
        });
      },
      quando: quando
    }
  };

  // Molde das secoes que sao apenas leitura tabular. Seis das onze secoes sao
  // exatamente isso, e escrever seis vezes o mesmo componente seria a mesma
  // duplicacao que este trabalho esta' desfazendo.
  function tabela(fonte, campo, colunas, chave) {
    return {
      mixins: [secao],
      data: function () {
        return { busca: '' };
      },
      computed: {
        linhas: function () {
          return lista(this.dados, campo);
        },
        colunas: function () {
          var self = this;
          return colunas.map(function (c) {
            return { text: self.$t(c.rotulo), value: c.campo, sortable: true };
          });
        }
      },
      methods: {
        fontes: function () {
          return [typeof fonte === 'function' ? fonte() : fonte];
        }
      },
      template: `
        <div>
          <dlp-estado :carregando="carregando" :erro="erro" @recarregar="recarregar" />
          <v-text-field v-model="busca" :label="$t('dlp.buscar')" prepend-inner-icon="fas fa-search"
                        outlined dense clearable hide-details class="mb-3" />
          <v-data-table :headers="colunas" :items="linhas" :search="busca"
                        :loading="carregando" :item-key="'${chave}'"
                        :no-data-text="$t('dlp.vazio')" dense />
        </div>`
    };
  }

  var componentes = {};

  // --------------------------------------------------------------------------
  // Faixa de estado: carregando / erro / recarregar.
  // --------------------------------------------------------------------------
  componentes['dlp-estado'] = {
    props: { carregando: Boolean, erro: String },
    template: `
      <div>
        <v-alert v-if="erro" type="error" dense text class="mb-3">
          {{ $t('dlp.falhaServico') }} <strong>{{ erro }}</strong>
          <v-btn small text @click="$emit('recarregar')">{{ $t('dlp.tentarDeNovo') }}</v-btn>
        </v-alert>
        <v-progress-linear v-if="carregando" indeterminate class="mb-2" />
      </div>`
  };

  // --------------------------------------------------------------------------
  // 1. Painel
  // --------------------------------------------------------------------------
  componentes['dlp-secao-painel'] = {
    mixins: [secao],
    methods: {
      fontes: function () {
        return ['/saude', '/painel?dias=30'];
      },
      barra: function (valor, maior) {
        return maior > 0 ? Math.max(2, Math.round((valor * 100) / maior)) : 0;
      },
      maior: function (itens) {
        return itens.reduce(function (m, i) { return Math.max(m, i.total || 0); }, 1);
      }
    },
    computed: {
      saude: function () { return (this.dados && this.dados[0]) || {}; },
      painel: function () { return (this.dados && this.dados[1]) || {}; },
      cartoes: function () {
        var s = this.saude;
        var avisos = s.notificacoes || {};
        return [
          { rotulo: 'dlp.incidentes', valor: inteiro(s, 'incidentes'), nota: 'dlp.noTotal' },
          { rotulo: 'dlp.esperandoRevisao', valor: inteiro(s, 'revisao_pendente'),
            nota: 'dlp.decisaoHumana' },
          { rotulo: 'dlp.emQuarentena', valor: inteiro(s.quarentena || {}, 'RETIDO'),
            nota: 'dlp.retidosNoCofre' },
          { rotulo: 'dlp.avisosNaFila', valor: inteiro(avisos, 'PENDENTE'),
            nota: 'dlp.emFalha', alerta: inteiro(avisos, 'FALHA') },
          { rotulo: 'dlp.regrasAtivas', valor: inteiro(s, 'regras'), nota: 'dlp.naPolitica' }
        ];
      },
      // Cada linha diz o que esta' ligado E, quando nao esta', o que liga.
      // Um "inativo" sem o motivo obriga o operador a abrir o log.
      componentesServico: function () {
        var s = this.saude;
        var correio = s.correio || {};
        var siem = s.siem || {};
        var origens = (s.descoberta || {}).origens || [];
        var edm = s.indices_edm || [];
        var idm = s.indices_idm || [];
        return [
          { nome: this.$t('dlp.correio'), ativo: !!correio.ativo,
            nota: correio.ativo ? 'relay ' + (correio.host || '')
                                : this.$t('dlp.correioDesligado') },
          { nome: this.$t('dlp.siem'), ativo: !!siem.ativo,
            nota: siem.ativo ? String(siem.formato || '').toUpperCase() + ' -> ' + (siem.host || '')
                             : this.$t('dlp.siemDesligado') },
          { nome: this.$t('dlp.descobertaRepouso'), ativo: origens.length > 0,
            nota: origens.length
                    ? origens.length + ': ' + origens.map(function (o) { return o.nome; }).join(', ')
                    : this.$t('dlp.semOrigem') },
          { nome: this.$t('dlp.indicesEdmIdm'), ativo: (edm.length + idm.length) > 0,
            nota: edm.length + ' EDM, ' + idm.length + ' IDM' }
        ];
      },
      agregados: function () {
        var p = this.painel;
        var self = this;
        return [
          { titulo: 'dlp.porSeveridade', itens: p.por_severidade || [] },
          { titulo: 'dlp.porCanal', itens: p.por_canal || [] },
          { titulo: 'dlp.porRegra', itens: p.por_regra || [] },
          { titulo: 'dlp.porUsuario', itens: p.por_usuario || [] }
        ].filter(function (a) { return a.itens.length > 0; })
         .map(function (a) { return { titulo: a.titulo, itens: a.itens, maior: self.maior(a.itens) }; });
      }
    },
    template: `
      <div>
        <dlp-estado :carregando="carregando" :erro="erro" @recarregar="recarregar" />
        <v-row dense class="mb-2">
          <v-col v-for="c in cartoes" :key="c.rotulo" cols="12" sm="6" md="2">
            <v-card outlined class="pa-4 fill-height">
              <div class="text-h4 font-weight-bold">{{ c.valor }}</div>
              <div class="text-subtitle-2">{{ $t(c.rotulo) }}</div>
              <div class="text-caption grey--text">
                <span v-if="c.alerta !== undefined">{{ c.alerta }} </span>{{ $t(c.nota) }}
              </div>
            </v-card>
          </v-col>
        </v-row>

        <v-card outlined class="mb-3">
          <v-card-title class="text-subtitle-1">{{ $t('dlp.componentes') }}</v-card-title>
          <v-list dense>
            <v-list-item v-for="c in componentesServico" :key="c.nome">
              <v-list-item-icon class="me-3">
                <v-chip x-small :color="c.ativo ? 'success' : 'grey'" dark>
                  {{ c.ativo ? $t('dlp.ativo') : $t('dlp.inativo') }}
                </v-chip>
              </v-list-item-icon>
              <v-list-item-content>
                <v-list-item-title>{{ c.nome }}</v-list-item-title>
                <v-list-item-subtitle>{{ c.nota }}</v-list-item-subtitle>
              </v-list-item-content>
            </v-list-item>
          </v-list>
        </v-card>

        <v-row dense>
          <v-col v-for="a in agregados" :key="a.titulo" cols="12" md="6">
            <v-card outlined class="pa-4 mb-2">
              <div class="text-subtitle-1 mb-2">{{ $t(a.titulo) }}</div>
              <div v-for="i in a.itens" :key="i.chave" class="mb-2">
                <div class="d-flex justify-space-between text-caption">
                  <span>{{ i.chave }}</span><span>{{ i.total }}</span>
                </div>
                <v-progress-linear :value="barra(i.total, a.maior)" height="8" rounded />
              </div>
            </v-card>
          </v-col>
        </v-row>
      </div>`
  };

  // --------------------------------------------------------------------------
  // 2. Incidentes — com filtro, detalhe e acoes
  // --------------------------------------------------------------------------
  componentes['dlp-secao-incidentes'] = {
    mixins: [secao],
    data: function () {
      return {
        filtros: { estado: '', severidade: '', canal: '' },
        aberto: null,
        detalhe: null,
        anotacao: '',
        responsavel: ''
      };
    },
    computed: {
      linhas: function () { return lista(this.dados, 'itens'); },
      colunas: function () {
        var self = this;
        return [
          { text: self.$t('dlp.quando'), value: 'momento' },
          { text: self.$t('dlp.severidade'), value: 'severidade' },
          { text: self.$t('dlp.canal'), value: 'canal' },
          { text: self.$t('dlp.regra'), value: 'regra_nome' },
          { text: self.$t('dlp.usuario'), value: 'usuario' },
          { text: self.$t('dlp.estado'), value: 'estado' },
          { text: '', value: 'acoes', sortable: false }
        ];
      },
      urlCsv: function () {
        return raiz() + '/relatorios/incidentes.csv?' + this.consulta();
      }
    },
    methods: {
      fontes: function () {
        return ['/incidentes?limite=100&' + this.consulta()];
      },
      consulta: function () {
        var f = this.filtros;
        return Object.keys(f).filter(function (k) { return f[k]; })
          .map(function (k) { return k + '=' + encodeURIComponent(f[k]); }).join('&');
      },
      abrir: function (item) {
        var self = this;
        self.aberto = item;
        self.detalhe = null;
        ler(raiz() + '/incidentes/' + encodeURIComponent(item.identificador))
          .then(function (d) { self.detalhe = d; })
          .catch(function (e) { self.$root.$emit('dlp-aviso', String(e.message || e), 'error'); });
      },
      mudarEstado: function (estado) {
        this.agir('POST', '/incidentes/' + encodeURIComponent(this.aberto.identificador) + '/estado',
                  { estado: estado }, this.$t('dlp.estadoAlterado'));
        this.aberto = null;
      },
      atribuir: function () {
        this.agir('POST', '/incidentes/' + encodeURIComponent(this.aberto.identificador) + '/atribuir',
                  { responsavel: this.responsavel }, this.$t('dlp.atribuido'));
        this.responsavel = '';
      },
      anotar: function () {
        this.agir('POST', '/incidentes/' + encodeURIComponent(this.aberto.identificador) + '/anotar',
                  { texto: this.anotacao }, this.$t('dlp.anotado'));
        this.anotacao = '';
      },
      cor: function (s) {
        return { ALTA: 'error', CRITICA: 'error', MEDIA: 'warning', BAIXA: 'grey' }[s] || 'grey';
      }
    },
    template: `
      <div>
        <dlp-estado :carregando="carregando" :erro="erro" @recarregar="recarregar" />
        <v-row dense class="mb-1">
          <v-col cols="12" sm="3">
            <v-text-field v-model="filtros.estado" :label="$t('dlp.estado')" outlined dense
                          hide-details clearable @change="recarregar" />
          </v-col>
          <v-col cols="12" sm="3">
            <v-text-field v-model="filtros.severidade" :label="$t('dlp.severidade')" outlined dense
                          hide-details clearable @change="recarregar" />
          </v-col>
          <v-col cols="12" sm="3">
            <v-text-field v-model="filtros.canal" :label="$t('dlp.canal')" outlined dense
                          hide-details clearable @change="recarregar" />
          </v-col>
          <v-col cols="12" sm="3" class="d-flex align-center">
            <v-btn small outlined :href="urlCsv" class="me-2">
              <v-icon x-small class="me-1">fas fa-download</v-icon>{{ $t('dlp.csv') }}
            </v-btn>
            <v-btn small text @click="recarregar">{{ $t('dlp.recarregar') }}</v-btn>
          </v-col>
        </v-row>

        <v-data-table :headers="colunas" :items="linhas" :loading="carregando"
                      item-key="identificador" :no-data-text="$t('dlp.vazio')" dense>
          <template v-slot:item.momento="{ item }">{{ quando(item.momento) }}</template>
          <template v-slot:item.severidade="{ item }">
            <v-chip x-small :color="cor(item.severidade)" dark>{{ item.severidade }}</v-chip>
          </template>
          <template v-slot:item.acoes="{ item }">
            <v-btn x-small text @click="abrir(item)">{{ $t('dlp.abrir') }}</v-btn>
          </template>
        </v-data-table>

        <v-dialog :value="!!aberto" max-width="820" @input="aberto = null">
          <v-card v-if="aberto">
            <v-card-title class="text-subtitle-1">
              {{ $t('dlp.incidente') }} {{ aberto.identificador }}
              <v-spacer />
              <v-btn icon small @click="aberto = null"><v-icon small>fas fa-times</v-icon></v-btn>
            </v-card-title>
            <v-divider />
            <v-card-text>
              <v-progress-linear v-if="!detalhe" indeterminate />
              <pre v-else class="dlp-json">{{ JSON.stringify(detalhe, null, 2) }}</pre>
              <v-row dense class="mt-2">
                <v-col cols="12" sm="6">
                  <v-text-field v-model="responsavel" :label="$t('dlp.responsavel')" outlined dense hide-details>
                    <template v-slot:append-outer>
                      <v-btn small text :disabled="!responsavel" @click="atribuir">{{ $t('dlp.atribuir') }}</v-btn>
                    </template>
                  </v-text-field>
                </v-col>
                <v-col cols="12" sm="6">
                  <v-text-field v-model="anotacao" :label="$t('dlp.anotacao')" outlined dense hide-details>
                    <template v-slot:append-outer>
                      <v-btn small text :disabled="!anotacao" @click="anotar">{{ $t('dlp.anotar') }}</v-btn>
                    </template>
                  </v-text-field>
                </v-col>
              </v-row>
            </v-card-text>
            <v-card-actions>
              <v-btn small text @click="mudarEstado('EM_ANALISE')">{{ $t('dlp.emAnalise') }}</v-btn>
              <v-btn small text @click="mudarEstado('CONFIRMADO')">{{ $t('dlp.confirmar') }}</v-btn>
              <v-btn small text @click="mudarEstado('FALSO_POSITIVO')">{{ $t('dlp.falsoPositivo') }}</v-btn>
              <v-spacer />
              <v-btn small text @click="mudarEstado('ENCERRADO')">{{ $t('dlp.encerrar') }}</v-btn>
            </v-card-actions>
          </v-card>
        </v-dialog>
      </div>`
  };

  // --------------------------------------------------------------------------
  // 3. Revisao — a fila que decide se o bloqueio cai ou fica
  // --------------------------------------------------------------------------
  componentes['dlp-secao-revisao'] = {
    mixins: [secao],
    data: function () {
      return { justificativa: '', horas: 24, usos: 1 };
    },
    computed: {
      linhas: function () { return lista(this.dados, 'itens'); },
      colunas: function () {
        var self = this;
        return [
          { text: self.$t('dlp.quando'), value: 'momento' },
          { text: self.$t('dlp.usuario'), value: 'usuario' },
          { text: self.$t('dlp.canal'), value: 'canal' },
          { text: self.$t('dlp.regra'), value: 'regra_nome' },
          { text: self.$t('dlp.arquivo'), value: 'nome_arquivo' },
          { text: '', value: 'acoes', sortable: false }
        ];
      }
    },
    methods: {
      fontes: function () { return ['/revisao?limite=100']; },
      aprovar: function (item) {
        this.agir('POST', '/revisao/' + encodeURIComponent(item.identificador) + '/aprovar',
                  { justificativa: this.justificativa, horas: Number(this.horas),
                    teto_usos: Number(this.usos) },
                  this.$t('dlp.aprovado'));
      },
      reprovar: function (item) {
        this.agir('POST', '/revisao/' + encodeURIComponent(item.identificador) + '/reprovar',
                  { justificativa: this.justificativa }, this.$t('dlp.reprovado'));
      }
    },
    template: `
      <div>
        <dlp-estado :carregando="carregando" :erro="erro" @recarregar="recarregar" />
        <v-alert type="info" dense text class="mb-3">{{ $t('dlp.revisaoExplica') }}</v-alert>
        <v-row dense class="mb-1">
          <v-col cols="12" sm="6">
            <v-text-field v-model="justificativa" :label="$t('dlp.justificativa')" outlined dense hide-details />
          </v-col>
          <v-col cols="6" sm="3">
            <v-text-field v-model="horas" :label="$t('dlp.horas')" type="number" outlined dense hide-details />
          </v-col>
          <v-col cols="6" sm="3">
            <v-text-field v-model="usos" :label="$t('dlp.tetoUsos')" type="number" outlined dense hide-details />
          </v-col>
        </v-row>
        <v-data-table :headers="colunas" :items="linhas" :loading="carregando"
                      item-key="identificador" :no-data-text="$t('dlp.filaVazia')" dense>
          <template v-slot:item.momento="{ item }">{{ quando(item.momento) }}</template>
          <template v-slot:item.acoes="{ item }">
            <v-btn x-small color="success" depressed class="me-1"
                   :disabled="!justificativa" @click="aprovar(item)">{{ $t('dlp.aprovar') }}</v-btn>
            <v-btn x-small color="error" depressed
                   :disabled="!justificativa" @click="reprovar(item)">{{ $t('dlp.reprovar') }}</v-btn>
          </template>
        </v-data-table>
      </div>`
  };

  // --------------------------------------------------------------------------
  // 4. Quarentena — o cofre proprio E o do add-on nativo, lado a lado
  //
  // INTEGRAR, NAO DUPLICAR. Esta instalacao tem DOIS cofres: o deste motor e o
  // do add-on de DLP da propria eXo (/rest/dlp/items). Antes, cada um so' era
  // visivel na sua propria tela, e o operador tinha de saber que existiam dois.
  // Aqui os dois aparecem no mesmo lugar, cada um identificado pela origem.
  // Uma falha no nativo NAO esconde o proprio, e vice-versa.
  // --------------------------------------------------------------------------
  componentes['dlp-secao-quarentena'] = {
    mixins: [secao],
    data: function () {
      return { justificativa: '', horas: 24, itensNativos: [], erroNativo: '' };
    },
    computed: {
      retidos: function () { return lista(this.dados && this.dados[0], 'itens'); },
      liberacoes: function () { return lista(this.dados && this.dados[1], 'itens'); },
      colunas: function () {
        var self = this;
        return [
          { text: self.$t('dlp.quando'), value: 'momento' },
          { text: self.$t('dlp.arquivo'), value: 'nome_arquivo' },
          { text: self.$t('dlp.usuario'), value: 'usuario' },
          { text: self.$t('dlp.regra'), value: 'regra_nome' },
          { text: '', value: 'acoes', sortable: false }
        ];
      },
      colunasLiberacao: function () {
        var self = this;
        return [
          { text: self.$t('dlp.quando'), value: 'momento' },
          { text: self.$t('dlp.usuario'), value: 'usuario' },
          { text: self.$t('dlp.expiraEm'), value: 'expira_em' },
          { text: self.$t('dlp.usos'), value: 'usos' },
          { text: '', value: 'acoes', sortable: false }
        ];
      },
      colunasNativas: function () {
        var self = this;
        return [
          { text: self.$t('dlp.arquivo'), value: 'title' },
          { text: self.$t('dlp.quando'), value: 'detectionDate' },
          { text: self.$t('dlp.regra'), value: 'keywords' }
        ];
      }
    },
    methods: {
      fontes: function () { return ['/quarentena?limite=100', '/liberacoes?limite=100']; },
      carregarNativo: function () {
        var self = this;
        self.erroNativo = '';
        ler(nativo() + '/items?offset=0&limit=100')
          .then(function (d) { self.itensNativos = lista(d, 'dlpItems'); })
          .catch(function (e) { self.erroNativo = String(e.message || e); });
      },
      baixar: function (item) {
        window.location.href = raiz() + '/quarentena/' + encodeURIComponent(item.identificador) + '/conteudo';
      },
      liberar: function (item) {
        this.agir('POST', '/quarentena/' + encodeURIComponent(item.identificador) + '/liberar',
                  { justificativa: this.justificativa, horas: Number(this.horas) },
                  this.$t('dlp.liberado'));
      },
      revogar: function (item) {
        this.agir('POST', '/liberacoes/' + encodeURIComponent(item.identificador) + '/revogar',
                  { justificativa: this.justificativa }, this.$t('dlp.revogado'));
      }
    },
    mounted: function () {
      this.carregarNativo();
    },
    template: `
      <div>
        <dlp-estado :carregando="carregando" :erro="erro" @recarregar="recarregar" />
        <v-row dense class="mb-1">
          <v-col cols="12" sm="8">
            <v-text-field v-model="justificativa" :label="$t('dlp.justificativa')" outlined dense hide-details />
          </v-col>
          <v-col cols="12" sm="4">
            <v-text-field v-model="horas" :label="$t('dlp.horas')" type="number" outlined dense hide-details />
          </v-col>
        </v-row>

        <v-card outlined class="mb-4">
          <v-card-title class="text-subtitle-1">{{ $t('dlp.cofreProprio') }}</v-card-title>
          <v-data-table :headers="colunas" :items="retidos" :loading="carregando"
                        item-key="identificador" :no-data-text="$t('dlp.vazio')" dense>
            <template v-slot:item.momento="{ item }">{{ quando(item.momento) }}</template>
            <template v-slot:item.acoes="{ item }">
              <v-btn x-small text class="me-1" @click="baixar(item)">{{ $t('dlp.baixar') }}</v-btn>
              <v-btn x-small color="success" depressed
                     :disabled="!justificativa" @click="liberar(item)">{{ $t('dlp.liberar') }}</v-btn>
            </template>
          </v-data-table>
        </v-card>

        <v-card outlined class="mb-4">
          <v-card-title class="text-subtitle-1">
            {{ $t('dlp.cofreNativo') }}
            <v-chip x-small outlined class="ms-2">{{ $t('dlp.addonExo') }}</v-chip>
          </v-card-title>
          <v-alert v-if="erroNativo" type="warning" dense text class="ma-3">
            {{ $t('dlp.nativoIndisponivel') }} <strong>{{ erroNativo }}</strong>
          </v-alert>
          <v-data-table :headers="colunasNativas" :items="itensNativos"
                        :no-data-text="$t('dlp.vazio')" dense>
            <template v-slot:item.detectionDate="{ item }">{{ quando(item.detectionDate) }}</template>
          </v-data-table>
        </v-card>

        <v-card outlined>
          <v-card-title class="text-subtitle-1">{{ $t('dlp.liberacoesVigentes') }}</v-card-title>
          <v-data-table :headers="colunasLiberacao" :items="liberacoes"
                        item-key="identificador" :no-data-text="$t('dlp.vazio')" dense>
            <template v-slot:item.momento="{ item }">{{ quando(item.momento) }}</template>
            <template v-slot:item.expira_em="{ item }">{{ quando(item.expira_em) }}</template>
            <template v-slot:item.acoes="{ item }">
              <v-btn x-small color="error" depressed
                     :disabled="!justificativa" @click="revogar(item)">{{ $t('dlp.revogar') }}</v-btn>
            </template>
          </v-data-table>
        </v-card>
      </div>`
  };

  // --------------------------------------------------------------------------
  // 5. Politica — tabela de regras e o texto integral
  // --------------------------------------------------------------------------
  componentes['dlp-secao-politica'] = {
    mixins: [secao],
    data: function () {
      return { texto: '', editando: false };
    },
    computed: {
      regras: function () { return lista(this.dados, 'regras'); },
      colunas: function () {
        var self = this;
        return [
          { text: self.$t('dlp.regra'), value: 'nome' },
          { text: self.$t('dlp.severidade'), value: 'severidade' },
          { text: self.$t('dlp.acao'), value: 'acoes' },
          { text: self.$t('dlp.prioridade'), value: 'prioridade' },
          { text: self.$t('dlp.ativa'), value: 'ativa' }
        ];
      }
    },
    methods: {
      fontes: function () { return ['/politica']; },
      editar: function () {
        this.texto = JSON.stringify(this.dados, null, 2);
        this.editando = true;
      },
      gravar: function () {
        var corpo;
        try {
          corpo = JSON.parse(this.texto);
        } catch (e) {
          this.$root.$emit('dlp-aviso', this.$t('dlp.jsonInvalido'), 'error');
          return;
        }
        this.editando = false;
        this.agir('PUT', '/politica', corpo, this.$t('dlp.politicaGravada'));
      }
    },
    template: `
      <div>
        <dlp-estado :carregando="carregando" :erro="erro" @recarregar="recarregar" />
        <div class="d-flex mb-2">
          <v-spacer />
          <v-btn v-if="!editando" small outlined @click="editar">{{ $t('dlp.editar') }}</v-btn>
          <template v-else>
            <v-btn small text class="me-1" @click="editando = false">{{ $t('dlp.cancelar') }}</v-btn>
            <v-btn small color="primary" depressed @click="gravar">{{ $t('dlp.gravar') }}</v-btn>
          </template>
        </div>
        <v-textarea v-if="editando" v-model="texto" outlined rows="24"
                    class="dlp-monoespaco" hide-details />
        <v-data-table v-else :headers="colunas" :items="regras"
                      item-key="nome" :no-data-text="$t('dlp.vazio')" dense>
          <template v-slot:item.acoes="{ item }">
            <v-chip v-for="c in (item.acoes || [])" :key="c" x-small outlined class="me-1">{{ c }}</v-chip>
          </template>
          <template v-slot:item.ativa="{ item }">
            <v-icon small :color="item.ativa ? 'success' : 'grey'">
              {{ item.ativa ? 'fas fa-check-circle' : 'fas fa-circle' }}
            </v-icon>
          </template>
        </v-data-table>
      </div>`
  };

  // --------------------------------------------------------------------------
  // 6. Indices EDM/IDM — desativar NAO apaga
  // --------------------------------------------------------------------------
  componentes['dlp-secao-indices'] = {
    mixins: [secao],
    computed: {
      linhas: function () { return (this.dados ? (this.dados.edm || []).map(function (i) { return Object.assign({ tipo: 'edm' }, i); }).concat((this.dados.idm || []).map(function (i) { return Object.assign({ tipo: 'idm' }, i); })) : []); },
      colunas: function () {
        var self = this;
        return [
          { text: self.$t('dlp.nome'), value: 'nome' },
          { text: self.$t('dlp.tipo'), value: 'tipo' },
          { text: self.$t('dlp.registros'), value: 'total_registros' },
          { text: self.$t('dlp.atualizado'), value: 'atualizado_em' },
          { text: self.$t('dlp.ativa'), value: 'ativo' }
        ];
      }
    },
    methods: {
      fontes: function () { return ['/indices']; },
      alternar: function (item) {
        this.agir('POST',
                  '/indices/' + encodeURIComponent(item.tipo) + '/' +
                  encodeURIComponent(item.nome) + '/estado',
                  { ativo: !item.ativo }, this.$t('dlp.estadoAlterado'));
      }
    },
    template: `
      <div>
        <dlp-estado :carregando="carregando" :erro="erro" @recarregar="recarregar" />
        <v-alert type="info" dense text class="mb-3">{{ $t('dlp.indicesExplica') }}</v-alert>
        <v-data-table :headers="colunas" :items="linhas" :loading="carregando"
                      item-key="nome" :no-data-text="$t('dlp.vazio')" dense>
          <template v-slot:item.atualizado_em="{ item }">{{ quando(item.atualizado_em) }}</template>
          <template v-slot:item.ativo="{ item }">
            <v-switch :input-value="item.ativo" dense hide-details class="mt-0"
                      @change="alternar(item)" />
          </template>
        </v-data-table>
      </div>`
  };

  // --------------------------------------------------------------------------
  // 7. Dicionarios
  // --------------------------------------------------------------------------
  componentes['dlp-secao-dicionarios'] = {
    mixins: [secao],
    data: function () {
      return { nome: '', termos: '' };
    },
    computed: {
      linhas: function () { return deMapa(this.dados && this.dados.cadastrados, 'nome'); },
      colunas: function () {
        var self = this;
        return [
          { text: self.$t('dlp.nome'), value: 'nome' },
          { text: self.$t('dlp.termos'), value: 'total' },
          { text: self.$t('dlp.atualizado'), value: 'atualizado_em' }
        ];
      }
    },
    methods: {
      fontes: function () { return ['/dicionarios']; },
      gravar: function () {
        var termos = this.termos.split(/[\n,;]+/).map(function (t) { return t.trim(); })
          .filter(function (t) { return t.length > 0; });
        var nome = this.nome;
        this.nome = '';
        this.termos = '';
        this.agir('PUT', '/dicionarios/' + encodeURIComponent(nome),
                  { termos: termos }, this.$t('dlp.dicionarioGravado'));
      }
    },
    template: `
      <div>
        <dlp-estado :carregando="carregando" :erro="erro" @recarregar="recarregar" />
        <v-card outlined class="pa-4 mb-4">
          <div class="text-subtitle-1 mb-2">{{ $t('dlp.novoDicionario') }}</div>
          <v-row dense>
            <v-col cols="12" sm="4">
              <v-text-field v-model="nome" :label="$t('dlp.nome')" outlined dense hide-details />
            </v-col>
            <v-col cols="12" sm="6">
              <v-textarea v-model="termos" :label="$t('dlp.termosAjuda')" outlined dense rows="2" hide-details />
            </v-col>
            <v-col cols="12" sm="2" class="d-flex align-center">
              <v-btn small color="primary" depressed :disabled="!nome || !termos"
                     @click="gravar">{{ $t('dlp.gravar') }}</v-btn>
            </v-col>
          </v-row>
        </v-card>
        <v-data-table :headers="colunas" :items="linhas" :loading="carregando"
                      item-key="nome" :no-data-text="$t('dlp.vazio')" dense>
          <template v-slot:item.atualizado_em="{ item }">{{ quando(item.atualizado_em) }}</template>
        </v-data-table>
      </div>`
  };

  // --------------------------------------------------------------------------
  // 8. Descoberta em repouso
  // --------------------------------------------------------------------------
  componentes['dlp-secao-descoberta'] = {
    mixins: [secao],
    data: function () {
      return { origem: '', completa: false };
    },
    computed: {
      origens: function () { return lista(this.dados && this.dados[0], 'itens'); },
      varreduras: function () { return lista(this.dados && this.dados[1], 'itens'); },
      colunas: function () {
        var self = this;
        return [
          { text: self.$t('dlp.quando'), value: 'momento' },
          { text: self.$t('dlp.origem'), value: 'origem' },
          { text: self.$t('dlp.estado'), value: 'estado' },
          { text: self.$t('dlp.lidos'), value: 'inspecionados' },
          { text: self.$t('dlp.incidentes'), value: 'com_achado' },
          { text: '', value: 'acoes', sortable: false }
        ];
      }
    },
    methods: {
      fontes: function () { return ['/descoberta/origens', '/descoberta/varreduras?limite=50']; },
      iniciar: function () {
        this.agir('POST', '/descoberta/varreduras',
                  { origem: this.origem, completa: this.completa },
                  this.$t('dlp.varreduraIniciada'));
      },
      cancelar: function (item) {
        this.agir('POST', '/descoberta/varreduras/' + encodeURIComponent(item.identificador) + '/cancelar',
                  {}, this.$t('dlp.varreduraCancelada'));
      }
    },
    template: `
      <div>
        <dlp-estado :carregando="carregando" :erro="erro" @recarregar="recarregar" />
        <v-alert v-if="!origens.length" type="warning" dense text class="mb-3">
          {{ $t('dlp.semOrigem') }}
        </v-alert>
        <v-card outlined class="pa-4 mb-4">
          <v-row dense align="center">
            <v-col cols="12" sm="5">
              <v-select v-model="origem" :items="origens" item-text="nome" item-value="nome"
                        :label="$t('dlp.origem')" outlined dense hide-details />
            </v-col>
            <v-col cols="12" sm="3">
              <v-checkbox v-model="completa" :label="$t('dlp.varreduraCompleta')" dense hide-details class="mt-0" />
            </v-col>
            <v-col cols="12" sm="4">
              <v-btn small color="primary" depressed :disabled="!origem"
                     @click="iniciar">{{ $t('dlp.iniciarVarredura') }}</v-btn>
            </v-col>
          </v-row>
        </v-card>
        <v-data-table :headers="colunas" :items="varreduras" :loading="carregando"
                      item-key="identificador" :no-data-text="$t('dlp.vazio')" dense>
          <template v-slot:item.momento="{ item }">{{ quando(item.momento) }}</template>
          <template v-slot:item.acoes="{ item }">
            <v-btn v-if="item.estado === 'EM_CURSO'" x-small text
                   @click="cancelar(item)">{{ $t('dlp.cancelar') }}</v-btn>
          </template>
        </v-data-table>
      </div>`
  };

  // --------------------------------------------------------------------------
  // 9. Avisos — a fila que nao pode falhar em silencio
  // --------------------------------------------------------------------------
  componentes['dlp-secao-avisos'] = {
    mixins: [secao],
    computed: {
      linhas: function () { return lista(this.dados, 'itens'); },
      colunas: function () {
        var self = this;
        return [
          { text: self.$t('dlp.quando'), value: 'momento' },
          { text: self.$t('dlp.destinatario'), value: 'destinatario' },
          { text: self.$t('dlp.tipo'), value: 'tipo' },
          { text: self.$t('dlp.estado'), value: 'estado' },
          { text: self.$t('dlp.tentativas'), value: 'tentativas' },
          { text: '', value: 'acoes', sortable: false }
        ];
      }
    },
    methods: {
      fontes: function () { return ['/notificacoes?limite=100']; },
      reenviar: function (item) {
        this.agir('POST', '/notificacoes/' + encodeURIComponent(item.id) + '/reenviar',
                  {}, this.$t('dlp.reenviado'));
      }
    },
    template: `
      <div>
        <dlp-estado :carregando="carregando" :erro="erro" @recarregar="recarregar" />
        <v-data-table :headers="colunas" :items="linhas" :loading="carregando"
                      item-key="identificador" :no-data-text="$t('dlp.vazio')" dense>
          <template v-slot:item.momento="{ item }">{{ quando(item.momento) }}</template>
          <template v-slot:item.estado="{ item }">
            <v-chip x-small :color="item.estado === 'FALHA' ? 'error' : 'grey'" dark>{{ item.estado }}</v-chip>
          </template>
          <template v-slot:item.acoes="{ item }">
            <v-btn v-if="item.estado === 'FALHA'" x-small text
                   @click="reenviar(item)">{{ $t('dlp.reenviar') }}</v-btn>
          </template>
        </v-data-table>
      </div>`
  };

  // --------------------------------------------------------------------------
  // 10 e 11. Agentes e Auditoria — leitura tabular
  // --------------------------------------------------------------------------
  componentes['dlp-secao-agentes'] = tabela('/agentes', 'agentes', [
    { rotulo: 'dlp.nome', campo: 'nome' },
    { rotulo: 'dlp.maquina', campo: 'sistema' },
    { rotulo: 'dlp.versao', campo: 'versao' },
    { rotulo: 'dlp.ultimoContato', campo: 'visto_em' },
    { rotulo: 'dlp.identificador', campo: 'identificador' }
  ], 'identificador');

  componentes['dlp-secao-auditoria'] = tabela('/auditoria?limite=200', 'auditoria', [
    { rotulo: 'dlp.quando', campo: 'momento' },
    { rotulo: 'dlp.autor', campo: 'autor' },
    { rotulo: 'dlp.acao', campo: 'acao' },
    { rotulo: 'dlp.alvo', campo: 'alvo' },
    { rotulo: 'dlp.detalhe', campo: 'detalhe' }
  ], 'id');

  // ==========================================================================
  // Raiz: as abas.
  //
  // A TROCA DE ABA E' NO CLIENTE. Nao ha' ida ao servidor, nao ha' parametro de
  // render e nao ha' recarga de pagina. Foi por tentar o contrario que a versao
  // anterior nunca trocou de aba nesta plataforma.
  // ==========================================================================
  var ABAS = [
    { codigo: 'painel', componente: 'dlp-secao-painel' },
    { codigo: 'incidentes', componente: 'dlp-secao-incidentes' },
    { codigo: 'revisao', componente: 'dlp-secao-revisao' },
    { codigo: 'quarentena', componente: 'dlp-secao-quarentena' },
    { codigo: 'politica', componente: 'dlp-secao-politica' },
    { codigo: 'indices', componente: 'dlp-secao-indices' },
    { codigo: 'dicionarios', componente: 'dlp-secao-dicionarios' },
    { codigo: 'descoberta', componente: 'dlp-secao-descoberta' },
    { codigo: 'avisos', componente: 'dlp-secao-avisos' },
    { codigo: 'agentes', componente: 'dlp-secao-agentes' },
    { codigo: 'auditoria', componente: 'dlp-secao-auditoria' }
  ];

  componentes['dlp-console-app'] = {
    data: function () {
      return { aba: 0, abas: ABAS, aviso: '', tipoAviso: 'success', mostrarAviso: false };
    },
    created: function () {
      var self = this;
      this.$root.$on('dlp-aviso', function (mensagem, tipo) {
        self.aviso = mensagem;
        self.tipoAviso = tipo || 'success';
        self.mostrarAviso = true;
      });
    },
    template: `
      <div class="dlp-console">
          <v-tabs v-model="aba" show-arrows class="dlp-abas">
            <v-tab v-for="a in abas" :key="a.codigo" :data-aba="a.codigo">
              {{ $t('dlp.aba.' + a.codigo) }}
            </v-tab>
          </v-tabs>
          <v-divider />
          <v-tabs-items v-model="aba" class="pa-4">
            <v-tab-item v-for="a in abas" :key="a.codigo" :eager="false">
              <component :is="a.componente" />
            </v-tab-item>
          </v-tabs-items>
          <v-snackbar v-model="mostrarAviso" :color="tipoAviso" timeout="5000">
            {{ aviso }}
          </v-snackbar>
      </div>`
  };

  for (var nome in componentes) {
    if (Object.prototype.hasOwnProperty.call(componentes, nome)) {
      Vue.component(nome, componentes[nome]);
    }
  }

  // Porta de extensao, no mesmo desenho que o add-on nativo abre. Outra
  // extensao pode acrescentar componentes a este console sem tocar neste WAR.
  if (typeof extensionRegistry !== 'undefined' && extensionRegistry) {
    var extras = extensionRegistry.loadComponents('dlp-console-app');
    if (extras && extras.length > 0) {
      extras.forEach(function (e) {
        Vue.component(e.componentName, e.componentOptions);
      });
    }
  }

  Vue.use(Vuetify);
  var vuetify = new Vuetify(eXo.env.portal.vuetifyPreset);

  return {
    init: function () {
      document.dispatchEvent(new CustomEvent('displayTopBarLoading'));
      var idioma = typeof eXo !== 'undefined' ? eXo.env.portal.language : 'en';
      var url = eXo.env.portal.context + '/' + eXo.env.portal.rest +
                '/i18n/bundle/locale.portlet.dlpconsole.Console-' + idioma + '.json';
      exoi18n.loadLanguageAsync(idioma, url).then(function (i18n) {
        Vue.createApp({
          mounted: function () {
            document.dispatchEvent(new CustomEvent('hideTopBarLoading'));
          },
          template: `<dlp-console-app id="${ID}" />`,
          vuetify: vuetify,
          i18n: i18n
        }, '#' + ID, 'DLP Console');
      });
    }
  };
});
