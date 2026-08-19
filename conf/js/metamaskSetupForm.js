/*
 * ARQUIVO VAZIO DE PROPOSITO — mesma situacao de loginCommon.bundle.js.
 *
 * O deeds-tenant.war declara o modulo `metamaskSetupForm` apontando para
 * /js/metamaskSetupForm.js, arquivo que a imagem oficial nao entrega (o war tem
 * 7 arquivos em js/, nenhum com esse nome), gerando a cada resolucao
 *     WARN  File not found: /js/metamaskSetupForm.js
 *
 * Diferenca em relacao ao loginCommon: NENHUM modulo depende deste. Ainda assim
 * a correcao escolhida foi a mesma — arquivo vazio em vez de remover a
 * declaracao — por ser a de menor risco: nao mexe no grafo de modulos da eXo.
 *
 * Este projeto nao usa login por Metamask (nao ha' no Ethereum nesta instalacao).
 */
