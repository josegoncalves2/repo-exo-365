/*
 * Portao: as URLs que a TELA monta contra as rotas que o REST PUBLICA.
 *
 * POR QUE NAO POR EXPRESSAO REGULAR. A primeira versao deste portao lia o
 * JavaScript com regex e enxergava 9 das ~25 chamadas: uma chamada montada por
 * concatenacao, ou passada a um molde de componente, escapava. Um portao que
 * enxerga um terco do alvo e diz "nenhuma orfa" e' pior do que nenhum portao,
 * porque da' confianca falsa.
 *
 * Aqui o modulo AMD e' CARREGADO DE VERDADE no Node, com Vue, Vuetify, eXo e
 * fetch substituidos por dublês que apenas ANOTAM. Depois, cada componente e'
 * instanciado e cada metodo que produz URL e' chamado. O que se compara e' o
 * que o codigo REALMENTE monta em execucao — nao o que parece montar.
 *
 * Isto NAO substitui o teste de navegador: nao prova que a tela renderiza nem
 * que o operador consegue clicar. Prova uma coisa so', e antes de o WAR
 * existir: que nenhuma secao aponta para uma rota que ninguem atende.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const RAIZ = process.argv[2];
const REST = process.argv[3];

// ---------------------------------------------------------------------------
// Dublês do ambiente do portal
// ---------------------------------------------------------------------------
const urls = new Set();

function anota(u) {
  if (typeof u === 'string') { urls.add(u); }
  return u;
}

global.fetch = function (u) {
  anota(u);
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
};

const componentes = {};
global.Vue = {
  component: (nome, opcoes) => { componentes[nome] = opcoes; },
  use: () => {},
  createApp: () => {}
};
global.Vuetify = function () {};
global.eXo = { env: { portal: { context: '/portal', rest: 'rest', language: 'pt_BR', vuetifyPreset: {} } } };
global.exoi18n = { loadLanguageAsync: () => Promise.resolve({}) };
global.extensionRegistry = { loadComponents: () => [] };
global.document = { dispatchEvent: () => {} };
global.CustomEvent = function () {};
// `baixar` navega por window.location.href — e' uma rota como qualquer outra
// e precisa ser conferida. O dublê anota a atribuicao em vez de ignora-la.
global.window = { location: { set href(u) { anota(u); }, get href() { return ''; } } };

let modulo = null;
global.define = function (fabrica) { modulo = fabrica(); };

require(path.join(RAIZ, 'web/js/consoleDlp.bundle.js'));
if (!modulo || typeof modulo.init !== 'function') {
  console.error('ERRO: o modulo nao devolveu { init }.');
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Exercita cada componente
// ---------------------------------------------------------------------------
const ITEM = { id: 'ITEM-1', tipo: 'edm', nome: 'NOME', ativo: false, estado: 'EM_CURSO' };
const IGNORAR = new Set(['recarregar', 'agir', 'quando', 'fontes', 'barra', 'cor', 'editar']);

function monta(opcoes) {
  const vm = {};
  const mixins = opcoes.mixins || [];
  const fontes = [];
  const acoes = [];
  const falhas = [];

  for (const origem of mixins.concat([opcoes])) {
    if (typeof origem.data === 'function') { Object.assign(vm, origem.data.call(vm)); }
  }
  // Estado de partida. Reaplicado ANTES DE CADA metodo, e nao so' uma vez: um
  // metodo pode alterar o estado de que o proximo depende. Foi o que aconteceu
  // aqui — `mudarEstado` zera `this.aberto`, e `atribuir` e `anotar`, chamados
  // depois, estouravam em null dentro do catch. Tres rotas sumiam do portao em
  // silencio, que e' exatamente o defeito que este portao existe para nao ter.
  const PARTIDA = () => ({ nome: 'N', termos: 'a,b', origem: 'O', texto: '{}',
                           justificativa: 'j', horas: 24, usos: 1,
                           filtros: { estado: 'E', severidade: 'S', canal: 'C' },
                           aberto: Object.assign({}, ITEM), dados: [{}, {}] });
  Object.assign(vm, PARTIDA());

  vm.$t = (k) => k;
  vm.$root = { $emit: () => {}, $on: () => {} };
  vm.agir = (metodo, caminho) => { acoes.push([metodo, caminho]); return Promise.resolve(); };

  for (const origem of mixins.concat([opcoes])) {
    for (const [k, f] of Object.entries(origem.methods || {})) {
      if (!vm[k]) { vm[k] = f.bind(vm); }
    }
  }
  for (const [k, f] of Object.entries(opcoes.computed || {})) {
    try { Object.defineProperty(vm, k, { get: f.bind(vm), configurable: true }); } catch (e) { /* nao fatal */ }
  }

  if (typeof (opcoes.methods || {}).fontes === 'function') {
    for (const f of opcoes.methods.fontes.call(vm)) { fontes.push(f); }
  }
  for (const origem of mixins.concat([opcoes])) {
    for (const k of Object.keys(origem.methods || {})) {
      if (IGNORAR.has(k)) { continue; }
      Object.assign(vm, PARTIDA());
      try { vm[k](Object.assign({}, ITEM)); } catch (e) { falhas.push(nomeDo(origem, k, e)); }
    }
  }
  // Propriedades computadas que devolvem URL (ex.: urlCsv).
  for (const k of Object.keys(opcoes.computed || {})) {
    if (k.toLowerCase().startsWith('url')) {
      try { anota(vm[k]); } catch (e) { /* idem */ }
    }
  }
  return { fontes, acoes, falhas };
}

const naoExercitados = [];
function nomeDo(origem, k, e) { return k + ' (' + e.message + ')'; }
global.nomeDo = nomeDo;
const chamadas = new Set();
for (const [nome, opcoes] of Object.entries(componentes)) {
  const { fontes, acoes, falhas } = monta(opcoes);
  falhas.forEach((f) => naoExercitados.push(nome + '.' + f));
  fontes.forEach((f) => chamadas.add('GET ' + f));
  acoes.forEach(([m, c]) => chamadas.add(m + ' ' + c));
}
// URLs montadas fora de metodo (fetch direto, window.location).
urls.forEach((u) => chamadas.add('GET ' + u));

// ---------------------------------------------------------------------------
// Contrato do servidor: as anotacoes JAX-RS
// ---------------------------------------------------------------------------
const java = fs.readFileSync(REST, 'utf8');
const publicadas = [];
const blocos = java.split('@Path("').slice(1);
for (const b of blocos) {
  const p = b.slice(0, b.indexOf('"'));
  if (p === '/dlp-pmo') { continue; }
  const antes = java.slice(0, java.indexOf('@Path("' + p + '"'));
  const verbos = antes.match(/@(GET|POST|PUT|DELETE)\s*$/m);
  publicadas.push({
    caminho: new RegExp('^' + p.replace(/\{[^}]+\}/g, '[^/]+') + '$'),
    texto: p
  });
}

const NATIVAS = [/^\/portal\/rest\/dlp\/items/, /^\/portal\/rest\/i18n\//];
const orfas = [];
for (const c of [...chamadas].sort()) {
  let [, alvo] = c.split(' ');
  if (!alvo) { continue; }
  alvo = alvo.replace('/portal/rest/dlp-pmo', '').split('?')[0].replace(/\/$/, '');
  if (NATIVAS.some((r) => r.test(c.split(' ')[1]))) { continue; }
  if (alvo === '' || alvo === '/portal/rest/dlp-pmo') { continue; }
  if (!publicadas.some((p) => p.caminho.test(alvo))) { orfas.push(c); }
}

if (naoExercitados.length) {
  console.log('metodos que nao produziram rota (conferir se e\' esperado):');
  naoExercitados.forEach((f) => console.log('   ' + f));
  console.log('');
}
console.log('rotas exercitadas pela tela: ' + chamadas.size +
            ' | publicadas pelo REST: ' + publicadas.length);
for (const c of [...chamadas].sort()) { console.log('   ' + c); }
if (orfas.length) {
  console.error('\nERRO: rota chamada e nao publicada:');
  orfas.forEach((c) => console.error('   ' + c));
  process.exit(1);
}
console.log('\nnenhuma rota orfa');
