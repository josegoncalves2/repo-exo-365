# EXO Community 7.2.1 — Administracao pela interface Web

> **REQUISITO OBRIGATORIO:** a entrega deve incluir videoconferencia nativa funcional
> no eXo Community. Ela nao e opcional, nao pode ser tratada como placeholder e nao
> pode depender de Jitsi, BigBlueButton ou outro produto externo para a funcionalidade
> basica. A entrega somente sera considerada completa depois que dois usuarios
> utilizarem uma sala pela interface Web, com audio, video, permissoes e encerramento
> comprovados. Integracoes externas podem existir como complemento, nunca como
> substituicao do recurso nativo obrigatorio.

## 1. Objetivo e criterio

Este documento confronta a configuracao oficial do eXo Platform com a stack implantada em `/opt/projetos/exo` e define o caminho para disponibilizar pela interface Web as funcoes hoje dependentes de CLI, `exo.properties`, XML ou Docker Compose.

Referencia oficial principal: [Configuration](https://docs.exoplatform.org/administration/configuration.html#configuration-overview).

A documentacao oficial afirma que a configuracao de implantacao deve ser feita em `exo.properties`, que algumas propriedades so podem ser definidas nesse arquivo e que configuracoes XML sao destinadas principalmente a desenvolvimento/extensao. Portanto, "toda funcionalidade na Web" nao significa expor segredos, heap, banco ou topologia como campos livres; significa oferecer uma camada administrativa segura para as configuracoes operacionais que fazem sentido em runtime.

### Classificacao usada

| Estado | Significado |
|---|---|
| **WEB-NATIVA** | O usuario executa a funcao pela UI existente, sem CLI. |
| **WEB-CONFIG** | A funcao e Web, mas sua configuracao administrativa ainda depende de arquivo, Compose ou restart. |
| **CLI/ARQUIVO** | Nao ha tela nativa segura; exige arquivo, script, Docker, SQL ou reinicio. |
| **EXTERNA** | Depende de outro produto, provedor ou credencial fora do eXo. |
| **NAO COMPROVADA** | Existe indicio, API ou webapp, mas falta prova funcional completa. |

`HTTP 200`, endpoint REST ou webapp presente nao sao provas suficientes. A prova de aceite deve executar a operacao, recuperar o resultado e conferir o efeito na UI.

## 2. Stack verificada

| Camada | Estado atual | Papel |
|---|---|---|
| eXo Platform | `exoplatform/exo-community:7.2.1`, JDK 21, imagem derivada com digest fixado | Portal, identidade, conteudo, sites, tarefas, agenda, social, analytics e gamificacao |
| Proxy | nginx `1.30.2-alpine` | Ponto unico de entrada HTTP e roteamento Matrix/ONLYOFFICE |
| Banco eXo | MySQL `8.4.9` | IDM, JCR e JPA |
| Busca | Elasticsearch `8.18.8` | Busca unificada e indexacao |
| Documentos Office | ONLYOFFICE DocumentServer `9.4` | Edicao online de DOCX, XLSX e PPTX |
| Chat | Matrix Synapse `v1.158.0` + PostgreSQL `16` | Mensageria integrada ao eXo |
| E-mail | Mailpit | Captura SMTP de testes; trocar por relay corporativo em producao |
| Persistencia | bind mounts em `data/` | Banco, binarios, codec, indices, Matrix e ONLYOFFICE |
| Operacao | `subir-ordenado.sh`, systemd e scripts de auditoria | Boot serial, saude, logs e testes |

### Limites importantes

- O eXo envia notificacoes por SMTP; nao fornece caixa postal, IMAP/POP3 ou webmail.
- O chat depende do Synapse. A webapp `matrix` sozinha nao e um servidor de chat.
- Videoconferencia deve ser entregue pelo recurso nativo Web Conferencing do eXo; Jitsi,
  BigBlueButton e outros provedores ficam como integracoes opcionais, nunca como
  dependencia obrigatoria da funcionalidade basica.
- SSO, LDAP/AD, Google Calendar, Outlook Calendar e SMTP corporativo exigem credenciais e decisao de integracao.
- A interface administrativa nao deve permitir alterar diretamente senha do banco, JWT, codec, chave da carteira, heap, volumes ou rotas do proxy.

## 3. Mapa funcional: o que ja esta na Web

| Dominio | Funcao para o usuario/admin | Caminho Web esperado | Estado nesta stack |
|---|---|---|---|
| Identidade | Login, logout, perfil e senha | Portal / perfil do usuario | **WEB-NATIVA**; login comprovado |
| Pessoas | Criar/editar usuarios, grupos e memberships | Administracao > Usuarios/Grupos | **WEB-NATIVA**, mas criacao via API ainda nao esta comprovada |
| Permissoes | Papeis, memberships, permissoes de espaco e site | Administracao e administracao do espaco | **WEB-NATIVA** para operacoes suportadas |
| Spaces | Criar espaco, membros, papeis, paginas e apps | Meu Espaco/Spaces | **WEB-NATIVA**; contrato de criacao REST pendente |
| Documentos | Upload, download, versoes, lixeira e permissao | Drive/Documents | **WEB-NATIVA**; WebDAV e apoio tecnico |
| Office | Abrir e editar DOCX/XLSX/PPTX | Abrir no ONLYOFFICE | **WEB-NATIVA**; persistencia de digitacao ainda precisa aceite final |
| Notes/Wiki | Criar, editar e publicar paginas | Notes/Wiki | **WEB-NATIVA**; API de criacao pendente |
| Tarefas | Projeto, tarefa, status, prazo e responsavel | Tasks | **WEB-NATIVA**; API de criacao pendente |
| Social | Publicacao, comentario, curtida, mencao e anexo | Activity Stream | **WEB-NATIVA**; feed comprovado |
| Busca | Buscar pessoas, documentos e atividades | Busca unificada | **WEB-NATIVA**; resultado deve ser validado por conteudo |
| Sites/CMS | Paginas, layout, conteudo, navegacao e publicacao | Administracao > Sites/Paginas | **WEB-NATIVA** |
| Agenda | Calendarios, eventos, recorrencia e iCal | Agenda | **WEB-NATIVA**; API de criacao pendente |
| Notificacoes | Preferencias, notificacoes Web e remetente | Administracao > Portal > Notificacoes | **WEB-CONFIG** para SMTP/filas; remetente tambem e UI |
| Gamificacao | Kudos, badges, pontos e placar | Gamification/Kudos/Wallet | **WEB-NATIVA**; carteira blockchain requer configuracao externa |
| Analytics | Painel de uso e engajamento | Analytics | **WEB-NATIVA**, aceite funcional ainda necessario |
| Apps | Lancador e integracoes | App Center/Integration | **WEB-NATIVA** para apps instalados |
| PWA/Push | Instalacao PWA e notificacao movel | Navegador/dispositivo | **WEB-CONFIG**; FCM exige arquivo de credencial |
| Chat | Conversas e anexos | Chat Matrix no portal | **WEB-NATIVA**; backend Matrix esta operacional |
| Video | Sala WebRTC nativa, chamada entre usuarios, audio, video e encerramento | `webconferencing.war`, `external-visio.war` | **OBRIGATORIA / WEB-NATIVA**; bloqueia a entrega ate passar no aceite funcional |
| E-mail | Notificacao de saida | UI + SMTP | **WEB-CONFIG**; caixas postais sao **EXTERNA** |

## 4. Mapa de configuracao oficial para a Web

A tabela abaixo resume os topicos da pagina oficial e a camada correta de destino.

| Topico oficial | Configuracao atual | Web administrativa necessaria |
|---|---|---|
| Portal, empresa, URL base e idioma | `exo.properties`, banco de sites e locale JVM | **WEB-CONFIG**: tela de Configuracoes Gerais deve gravar configuracao suportada; URL/locale efetivo precisam diagnostico |
| Cadastro publico e reset de senha | `exo.public.registration.enabled`, `exo.portal.resetpassword.expiretime` | **WEB-CONFIG**: toggle, prazo e teste de e-mail |
| Usuarios, grupos e validadores | IDM/UI; regex em `exo.properties` | Usuarios/grupos ja sao **WEB-NATIVA**; validadores devem ser **WEB-CONFIG** com validacao e auditoria |
| SMTP de saida e remetente | `EXO_MAIL_SMTP_*`, propriedades e tela de notificacoes | Segredos em Secret Store; UI apenas testa conexao, altera remetente e fila |
| Notificacoes Web/daily/weekly | propriedades Quartz/Notification | **WEB-CONFIG**: habilitar, horario, lote, retencao e teste |
| Documentos e upload | `exo.ecms.connector.drives.uploadLimit`, UI de drives | Limite e politicas de drive em **WEB-CONFIG**; nao expor caminho de filesystem |
| Versionamento | `exo.ecms.documents.versioning*` | **WEB-CONFIG** por drive: habilitar, maximo e expiracao |
| Viewer/PDF | `exo.ecms.documents.pdfviewer.*` | **WEB-CONFIG**: tamanho e paginas, com alerta de custo |
| Web Conferencing nativo | `webconferencing.*` e configuracao WebRTC nativa | **OBRIGATORIA**: provisionar, habilitar, testar sala, audio/video, permissoes e encerramento; provedores externos sao opcionais |
| WebDAV | `exo.webdav.*` e proxy | **CLI/ARQUIVO** para topologia; UI pode habilitar/desabilitar e mostrar URL/permissoes |
| JODConverter | `exo.jodconverter.*` | **CLI/ARQUIVO**; status e teste devem ser Web, portas nao devem ser editaveis livremente |
| Busca/Elasticsearch | `EXO_ES_*`, shards, replicas, reindex | **WEB-CONFIG**: status, reindex, conectores e limites; credenciais/cluster ficam fora da UI |
| Busca fuzzy e MIME types | `exo.unified-search.*` | **WEB-CONFIG** com validacao e tarefa de reindexacao |
| JCR, storage e data directory | Compose, `EXO_DATA_DIR`, `exo.jcr.*` | **CLI/ARQUIVO**; UI somente mostra estado, uso e alerta de backup |
| Cache | `exo.cache.*` em `exo.properties`/XML | **WEB-CONFIG** somente para allowlist de caches e TTL seguro; exigir preview de impacto e restart controlado |
| Quartz/jobs | `exo.quartz.*`, cron em propriedades | **WEB-CONFIG**: lista, proxima execucao, pausar/executar e logs; topologia do scheduler continua arquivo |
| Tarefas | `exo.tasks.default.status` | **WEB-NATIVA** para workflow de projeto; defaults globais devem virar **WEB-CONFIG** |
| Agenda | `exo.agenda.*`, Google/Office connector | UI para preferencias; credenciais OAuth e chaves em Secret Store/configuracao protegida |
| Chat Matrix | `meeds.matrix.*`, Synapse, proxy | UI para habilitar, endpoint/saude e politicas; segredo JWT, homeserver e proxy continuam infraestrutura |
| Wallet/recompensas | `exo.wallet.*`, codec e blockchain | UI para permissao, rede e limites; chave admin e codec sao **CLI/ARQUIVO imutavel** apos primeiro uso |
| Logs/auditoria | Docker, `platform.log`, `verificar-logs.sh` | **WEB-CONFIG** para nivel e consulta; retencao, filesystem e portao continuam operacao |
| Backup/restore | scripts, mysqldump, bind mounts | **CLI/ARQUIVO** com futura UI de job e download controlado; nunca restaurar sem confirmacao explicita |
| Add-ons/imagem | Dockerfile, Compose, webapps | **CLI/ARQUIVO**; App Center pode administrar apps compativeis, nao a imagem base |

## 5. Arquitetura proposta para cobertura Web

### 5.1 Principio

Criar um **EXO Administration Console** dentro do portal, usando as permissoes `/platform/administrators`, com quatro tipos de operacao:

1. **Leitura:** estado efetivo, origem da configuracao, ultima alteracao, impacto e saude.
2. **Configuracao runtime:** valores que o produto suporta alterar sem recriar container.
3. **Operacao:** reindexar, testar SMTP, testar Matrix/ONLYOFFICE, executar job, testar videoconferencia nativa e invalidar cache.
4. **Delegacao:** configurar integracoes externas com segredo mascarado e rotacao controlada.

A camada deve chamar servicos internos ou API administrativa versionada. Nao deve editar MySQL, arquivos de dados ou XML diretamente a partir do navegador.

### 5.2 Modulos da interface

| Modulo | Entregas |
|---|---|
| Visao geral | 8 servicos, versoes, healthchecks, uso de disco/memoria, alertas e ultima verificacao |
| Configuracoes gerais | nome, empresa, URL base, idioma, fuso, cadastro, reset e limites |
| Pessoas e acesso | usuarios, grupos, memberships, papeis, politicas de senha e auditoria |
| Conteudo | drives, upload, versionamento, viewer, WebDAV e lixeira |
| Busca | conectores, MIME types, fuzzy, status do ES e reindexacao |
| Comunicacao | SMTP, remetente, notificacoes, Matrix e Web Conferencing nativo obrigatorio |
| Jobs | Quartz/Notification/TrashCleaner: proxima execucao, executar agora, historico |
| Seguranca | SSO/LDAP/OAuth/SAML, certificados, politicas, sessoes e login history |
| Observabilidade | logs filtrados, eventos de auditoria, diagnostico e exportacao sem segredos |
| Integracoes | ONLYOFFICE, Google/Office Calendar, FCM e provedores externos opcionais |
| Backup | criar/verificar backup, politica de retencao e restauracao com dupla confirmacao |

### 5.3 Requisitos de seguranca

- RBAC por modulo e acao: `read`, `write`, `operate`, `secret-admin`.
- MFA/SSO para administradores quando a integracao estiver disponivel.
- Segredos nunca retornam em GET; exibicao mascarada e rotacao explicita.
- Toda mudanca registra ator, data, valor anterior mascarado, novo valor mascarado, origem e resultado.
- Validar chaves conhecidas, enumeracoes, limites e dependencias antes de salvar.
- Usar staged configuration: validar, mostrar diff, aplicar, healthcheck e permitir rollback.
- Mudancas que exigem restart mostram janela, impacto, backup recomendado e estado pendente.
- Proibir edicao Web de `EXO_DB_PASSWORD`, `MATRIX_JWT_SECRET`, `ONLYOFFICE_JWT_SECRET`, codec, chave da carteira, volumes, `mem_limit`, `nginx.conf` e comandos arbitrarios.
- CSRF, rate limit, sessao administrativa curta e trilha append-only.

## 6. Roadmap de implementacao

### Fase 0 — contrato e inventario

- Congelar a matriz desta documentacao por versao (`7.2.1`).
- Descobrir contratos REST reais e separar endpoints publicos, administrativos e inexistentes.
- Criar schema versionado de configuracao com `source`, `scope`, `type`, `secret`, `restartRequired` e `validation`.
- Definir quais valores sao runtime e quais pertencem ao Compose/host.

### Fase 1 — cobertura de alto valor

- Configuracoes Gerais: idioma, fuso, nome, URL base, cadastro e reset.
- Usuarios/grupos/memberships e auditoria.
- Drives: upload, versoes, permissao e lixeira.
- Notificacoes/SMTP: teste real e leitura no Mailpit em homologacao.
- Busca: saude e reindexacao.

### Fase 2 — integracoes e videoconferencia obrigatoria

- **Web Conferencing nativo:** provisionar, habilitar e validar T-14; a fase nao pode ser encerrada sem esse aceite.
- Matrix: status, sincronizacao, politica de anexos e teste de mensagem.
- ONLYOFFICE: status, JWT, teste de abrir/salvar.
- Agenda: conectores OAuth.
- Jitsi/BBB/STUN/TURN: somente integracoes complementares, se necessarias.
- LDAP/OAuth/SAML: fluxo de validacao e rollback.

### Fase 3 — operacao controlada

- Jobs, logs, diagnostico, backup verificavel e restauracao com aprovacao.
- Configuracoes de cache e limites com simulacao de impacto.
- App Center com allowlist e assinatura de artefatos.
- API administrativa documentada com OpenAPI e testes de permissao.

## 7. Matriz de aceite

Uma funcao so passa quando houver prova Web e prova de maquina:

| ID | Aceite Web | Aceite tecnico |
|---|---|---|
| AW-01 | Admin altera idioma e usuario anonimo ve a nova localizacao | banco/sites/JVM efetivos coerentes; restart somente se necessario |
| AW-02 | Admin cria usuario, atribui grupo e novo usuario entra | identidade retornada e permissao conferida |
| AW-03 | Admin altera limite de upload e usuario envia arquivo no limite | tamanho aplicado e arquivo recuperado com SHA-256 |
| AW-04 | Admin configura versionamento e usuario cria duas versoes | historico e politica de expiracao conferidos |
| AW-05 | Admin testa SMTP e dispara convite | mensagem real recebida e corpo conferido no Mailpit/relay |
| AW-06 | Admin testa Matrix e dois usuarios trocam mensagem/anexo | Synapse responde, mensagem e anexo recuperados |
| AW-07 | Admin testa ONLYOFFICE, usuario edita e reabre arquivo | marcador digitado persiste no OOXML |
| AW-08 | Admin reindexa um drive e busca o documento na UI | ES green, documento indexado e resultado com conteudo |
| AW-09 | Admin agenda/executa job e consulta resultado | Quartz registra execucao e efeito observado |
| AW-10 | Admin cria backup e valida restauracao isolada | dump, codec e binarios restaurados sem perda |
| AW-11 | Usuario sem permissao tenta cada modulo administrativo | 403/negacao na UI e evento de auditoria |
| AW-12 | Aplicacao de configuracao invalida e rollback | valor anterior preservado, healthcheck e diff comprovados |
| AW-13 | Dois usuarios entram na sala nativa, ativam audio/video, trocam comunicacao e encerram | navegador confirma tracks de audio/video, sala, permissao e encerramento; nenhum provedor externo e exigido |

## 8. Procedimento operacional atual

Enquanto o console nao existir, a fonte de verdade e:

- configuracao eXo: `conf/exo.properties`;
- infraestrutura: `docker-compose.yml`, `.env`, `Dockerfile.exo` e `conf/nginx.conf`;
- Matrix: `data/synapse/homeserver.yaml` e `scripts/setup-matrix.sh`;
- inicializacao: `scripts/subir-ordenado.sh`;
- testes: `tests/run_all.sh`;
- logs: `scripts/verificar-logs.sh`;
- trilha: `AUDIT.md` e `evidence/`.

Nunca aplicar configuracao alterando o container em execucao: ela desaparece no recreate e pode destruir uma webapp se for montada sob `/opt/exo/webapps/<app>/`. Correcoes em WAR devem ocorrer no build da imagem; dados devem ser alterados pela UI/servico suportado ou por procedimento de migracao documentado.

## 9. Conclusao executiva

A stack ja entrega uma superficie Web ampla para uso colaborativo. O deficit nao e a ausencia geral de funcionalidades, mas a falta de uma camada Web para administrar configuracoes de implantacao, integracoes, jobs, diagnostico e operacoes destrutivas com seguranca.

A recomendacao e manter Docker/host/segredos como camada de infraestrutura e construir no eXo um console administrativo com contrato versionado, RBAC, staged configuration, auditoria, healthcheck e rollback. Assim a equipe deixa de depender de CLI para operacoes recorrentes sem transformar a interface em um editor perigoso de arquivos de producao.
