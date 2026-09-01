# Por que `test_dlp_integration.sh` saiu de cena

O arquivo `test_dlp_integration.sh` foi aposentado em 2026-08-31 (renomeado
para `.OBSOLETO`, nao apagado, para a trilha ficar auditavel).

Ele **nao testava o eXo**. Reimplementava a regex de CPF/CNPJ em Python,
dentro do proprio script, e a rodava contra uma string literal escrita ali
mesmo. Passava sempre — inclusive nos dias em que o DLP da plataforma estava
inerte, que era o caso desde que a extensao foi instalada:

* o `ConectorDlpRegex` era descartado a cada boot (`<priority>100</priority>`
  com `addConnector` first-wins);
* `AcaoEnfileirarDlp` enfileirava com `entityType` e `entityId` trocados, o que
  estourava `NullPointerException` em `processBulk` e abortava o bulk inteiro.

Resultado: `DLP_QUEUE` parada, `DLP_POSITIVE_ITEMS` vazia, nenhum documento
varrido — e um teste verde o tempo todo.

O substituto e' `tests/test_07_dlp.py`, que grava um arquivo com CPF valido
pela mesma API que o usuario usa, espera o job de varredura e cobra a linha em
`DLP_POSITIVE_ITEMS`. Nao ha regex nenhuma dentro dele: quem tem de detectar
e' a plataforma.
