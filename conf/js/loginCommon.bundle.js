/*
 * ARQUIVO VAZIO DE PROPOSITO — nao acrescenta comportamento nenhum.
 *
 * Defeito de empacotamento da imagem oficial eXo 7.2.1: o social.war DECLARA o
 * modulo `loginCommon` em WEB-INF/gatein-resources.xml apontando para
 * /js/loginCommon.bundle.js, mas o build da eXo NAO entrega esse arquivo dentro
 * do war (149 arquivos em js/, nenhum com esse nome). A cada resolucao do modulo
 * o portal registra
 *     WARN  File not found: /js/loginCommon.bundle.js
 *          [o.g.p.controller.resource.script.Module]
 *
 * Nao da' para simplesmente apagar a declaracao: SETE declaracoes de modulo em
 * TRES war dependem de `loginCommon` (social.war 5x, deeds-tenant.war 1x,
 * documents-portlet.war 1x); remove-la trocaria este aviso por dependencia nao
 * resolvida.
 *
 * Como o modulo nao existe no artefato oficial, ele hoje contribui com ZERO
 * javascript — e a tela de login funciona assim, comprovadamente. Um arquivo
 * vazio expressa exatamente isso: zero javascript, mesmo comportamento de hoje,
 * sem o alarme falso. NAO e' um "shim" que finge implementar algo.
 *
 * O Dockerfile REPROVA O BUILD se uma versao futura da imagem passar a entregar
 * o arquivo de verdade, para que este vazio nunca sobrescreva o oficial.
 */
