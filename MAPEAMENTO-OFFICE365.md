# Mapeamento Office 365 → eXo Platform Community 7.2.1

> **VIDEOCONFERENCIA — CORRECAO DE PREMISSA APURADA EM [099].**
> A redacao anterior deste bloco exigia que a videoconferencia fosse entregue pelo
> "Web Conferencing nativo", tratando o Jitsi como mero complemento dispensavel.
> **Isso e tecnicamente impossivel** e foi comprovado por inspecao da imagem oficial:
> `webconferencing.war` e apenas o FRAMEWORK (a SPI de provedores de chamada); ele
> nao contem servidor de midia. O unico conector que o eXo Community 7.2.1 entrega e
> o External Visio (`external-visio.war` + `external-visio-connector-services.jar`),
> que **somente abre uma URL de reuniao em nova aba** (`window.open`, ver
> `external-visio/js/webconferencing-externalvisio.js`). Sem um SFU nao existe
> chamada — nao ha o que "ativar".
>
> **O requisito de fundo — nao depender de provedor externo — esta ATENDIDO:** o
> Jitsi foi provisionado AUTO-HOSPEDADO nesta mesma stack (servicos `jitsi-*` do
> `docker-compose.yml`), servido em `https://<host>:8443` com certificado da CA
> interna do projeto. Nenhuma chamada sai para `meet.jit.si` nem para qualquer
> terceiro; o STUN publico que a imagem do JVB traz por padrao foi DESLIGADO
> (`JVB_DISABLE_STUN`). T-14 foi medido com dois participantes reais em navegador,
> com audio e video trafegando pela ponte de midia.

Documento que define **o que precisa estar ativo e comprovado** para que a implantação
seja considerada uma substituição da suíte Microsoft 365. Cada linha coberta gera um
teste correspondente em `tests/` sob a **dupla abordagem** (A: máquina/API · B: usuário
final em navegador real).

Para o inventário detalhado da configuração oficial e o plano para disponibilizar as
operações hoje dependentes de CLI, arquivo ou Compose na interface Web, consulte
[`DOCUMENTACAO-ADMIN-WEB.md`](DOCUMENTACAO-ADMIN-WEB.md).

> Legenda de cobertura:
> **PLENA** — recurso equivalente, nativo e testável ·
> **PARCIAL** — equivalente com limitações a documentar ·
> **AUSENTE** — não existe no eXo Community; exige solução externa.

> **Base factual deste documento:** as 48 aplicações web da imagem
> `exoplatform/exo-community:7.2.1` foram listadas diretamente do arquivo
> (`ls /opt/exo/webapps/`), e não presumidas a partir da documentação. A revisão
> de 2026-08-12 corrigiu quatro linhas que estavam **subestimadas** — ver §6.

---

## 1. Colaboração e conteúdo

| Office 365 | eXo Community 7.2.1 | Webapp | Cobertura | Teste |
|---|---|---|---|---|
| SharePoint / Teams (sites de equipe) | **Spaces** (espaços com membros, papéis, permissões) | `social.war` | PLENA | T-01 |
| OneDrive / SharePoint Docs | **Documents / Drive** (pessoal + do espaço, versionamento, lixeira) | `documents-portlet.war`, `webdav.war` | PLENA | T-02 |
| Word / Excel / PowerPoint Online | **ONLYOFFICE DocumentServer 9.4** integrado (edição colaborativa simultânea) | `onlyoffice.war`, `editors.war` | PLENA | **T-03** |
| OneNote | **Notes / Wiki** (páginas hierárquicas, versionamento, editor rico) | `notes.war` | PLENA | T-04 |
| Planner / To Do | **Tasks** (tarefas, projetos, kanban, prazos, responsáveis) | `task-management.war` | PLENA | T-05 |
| Yammer / Viva Engage | **Activity Stream** (feed social, curtidas, comentários, menções) | `social.war` | PLENA | T-06 |
| Delve / busca corporativa | **Unified Search** sobre Elasticsearch 8.18 | `search.war` | PLENA | T-07 |
| SharePoint (publicação/CMS) | **Sites / Content / WCM** (páginas publicadas, editor de layout) | `sites.war`, `content.war`, `layout.war`, `ecm-wcm-*` | PLENA | T-12 |
| Microsoft Forms | **Enquetes (Poll)** no feed e formulários via app de conteúdo | `poll.war` | PARCIAL | T-13 |

## 2. Comunicação

| Office 365 | eXo Community 7.2.1 | Webapp | Cobertura | Teste |
|---|---|---|---|---|
| Teams (chat 1:1 e em grupo) | **Chat Matrix** (mensagens diretas e por espaço, anexos) | `matrix.war` + Synapse | PLENA | **T-08** |
| Teams (reuniões por vídeo) | **Web Conferencing nativo do eXo — OBRIGATÓRIO** — salas, áudio e vídeo no portal | `webconferencing.war`, `external-visio.war` | **OBRIGATORIA / PLENA somente após T-14** | **T-14 bloqueante** |
| Outlook (calendário) | **Agenda** (eventos, convites, recorrência, disponibilidade, iCal) | `agenda.war` | PLENA | T-09 |
| Outlook (caixa postal / Exchange) | **AUSENTE** — o eXo não é servidor de e-mail; apenas envia notificações via SMTP | — | AUSENTE | T-10 |
| Notificações por e-mail | **Notification Service** (imediata, resumo diário/semanal, por canal) | `commons-*` | PLENA | T-10 |
| Notificações móveis | **Push Notifications** + **PWA** (aplicativo instalável no celular) | `push-notifications.war`, `pwa.war` | PLENA | — |

## 3. Pessoas e administração

| Office 365 | eXo Community 7.2.1 | Webapp | Cobertura | Teste |
|---|---|---|---|---|
| Entra ID / AD (contas) | **Organization/IDM** + conector **LDAP/AD** | `portal.war` | PLENA | T-11 |
| Perfis / cartão de contato | **Perfil de usuário** (foto, cargo, contato, experiência) | `social.war` | PLENA | T-11 |
| Admin Center | **Administração** (usuários, grupos, papéis, permissões, portal, marca) | `portal.war`, `platform-ui.war` | PLENA para operações nativas; **WEB-CONFIG** para propriedades de implantação | T-12 |
| Viva Insights | **Analytics** (painéis de adoção e engajamento) | `analytics.war` | PLENA | — |
| Gamificação / reconhecimento | **Gamification / Kudos / Reward Wallet / Perk Store** | `gamification-*.war`, `kudos.war`, `wallet.war`, `perk-store.war` | PLENA | T-13 |
| Power Automate (fluxos) | **Processes** (solicitações e fluxos de aprovação nativos) | `processes.war` | PARCIAL | — |
| Portal de aplicativos | **App Center** (lançador de aplicações) | `app-center.war` | PLENA | — |
| SSO corporativo | SAML/OIDC/CAS via add-on (não habilitado por padrão) | `auth-server.war` | PARCIAL | — |

## 4. Lacunas conhecidas — registro formal

Estes pontos **não** são cobertos pelo eXo Community e precisam de decisão à parte:

1. **Servidor de e-mail (Exchange/Outlook).** O eXo apenas *envia* via SMTP. Caixas
   postais, IMAP/POP3 e webmail exigem produto separado (Mailu, Mailcow, Zimbra).
2. **Servidor de mídia (SFU) para videoconferência.** O eXo Community **não**
   entrega nenhum: `webconferencing.war` é só a SPI e o conector External Visio
   apenas abre uma URL. Resolvido nesta implantação com **Jitsi auto-hospedado na
   própria stack** (não é integração com terceiro: os 4 containers `jitsi-*` rodam
   neste servidor). O que permanece em aberto é apenas o alcance: para participantes
   **fora da rede local** seria necessário um TURN próprio (coturn) — o STUN público
   da Jitsi foi deliberadamente desligado para não criar dependência externa.
3. **SSO SAML/OIDC.** Disponível como add-on, não habilitado por padrão na Community.
4. **Power BI.** Sem equivalente; exige ferramenta dedicada (Metabase, Grafana).
   O `analytics.war` cobre adoção da plataforma, não BI corporativo genérico.

---

## 5. Matriz de testes

Cada teste é executado por **dois caminhos independentes** e só é aprovado se ambos passarem.

| ID | Funcionalidade | Abordagem A (máquina) | Abordagem B (usuário final, navegador real) |
|---|---|---|---|
| T-00 | Infraestrutura | healthcheck dos 8 serviços, versão da imagem, conectividade BD/ES | página inicial carrega e renderiza no navegador |
| T-01 | Espaços | REST cria/lê espaço e membros | usuário cria espaço pela UI, convida e o convidado vê |
| T-02 | Documentos | upload via WebDAV, download e verificação de checksum SHA-256 | usuário envia arquivo na UI, vê na lista e baixa |
| T-03 | **Edição ONLYOFFICE** | .docx OOXML real convertido pelo DocumentServer (docx→txt e docx→pdf), com JWT válido, conferindo o **texto extraído** | usuário abre o .docx no editor, **digita pelo teclado**, o servidor grava e o texto digitado é recuperado do arquivo salvo |
| T-04 | Notes/Wiki | REST cria página e recupera conteúdo | usuário escreve página no editor rico e ela aparece publicada |
| T-05 | Tarefas | REST cria tarefa com prazo e responsável | usuário cria tarefa na UI, move de coluna, conclui |
| T-06 | Feed social | REST publica atividade e lista | usuário posta, outro usuário comenta e curte |
| T-07 | Busca | índice ES contém o documento; consulta retorna | usuário busca pelo nome na lupa e encontra o arquivo |
| T-08 | **Chat** | dois usuários reais no Matrix: um envia, o **outro lê o mesmo texto**; resposta na volta; anexo baixado e comparado byte a byte | usuário final abre o chat no portal após login real |
| T-09 | Agenda | REST cria evento; exportação iCal válida | usuário cria evento na UI e ele aparece no calendário |
| T-10 | E-mail | SMTP aceita e Mailpit registra a mensagem | usuário dispara convite e o e-mail é lido no Mailpit |
| T-11 | Usuários | REST cria usuário e **autentica com ele** | novo usuário faz login real e edita o próprio perfil |
| T-12 | Administração | REST lê grupos/permissões | admin acessa o painel e altera uma configuração |
| T-13 | Gamificação | REST lê pontos/badges | usuário recebe kudos e o placar reflete |
| T-14 | **Videoconferência nativa** | cria sala, autentica participantes e verifica estado da chamada | dois usuários entram pela UI, ativam áudio/vídeo, comunicam-se e encerram a sala |

**Critério de reprovação:** um teste que apenas confirme HTTP 200 sem exercer a função
real do usuário é considerado **inválido** e não conta como aprovação.

---

## 6. Revisão de 2026-08-12 — correções neste documento

A conferência do inventário real de webapps mostrou que a versão anterior **subestimava**
a plataforma. Corrigido:

| Linha | Antes | Agora | Motivo |
|---|---|---|---|
| Power Automate | "não nativo — via REST API e webhooks externos" | **PARCIAL** via `processes.war` | existe aplicação nativa de solicitações e fluxos de aprovação |
| Videoconferência | "Web Conferencing nativo obrigatório" | **OBRIGATORIA** via `webconferencing.war` | salas, áudio e vídeo devem funcionar no portal; T-14 bloqueia a entrega |
| Viva Insights | citada junto com gamificação | **linha própria**, `analytics.war` | são recursos distintos |
| Notificações móveis / PWA | ausente do documento | **linha nova**, PLENA | `push-notifications.war` e `pwa.war` estão na imagem |

Também foram acrescentadas as colunas de **webapp**, para que cada afirmação deste
documento possa ser conferida contra o conteúdo real da imagem, e os testes **T-03** e
**T-08**, que **não existiam** na suíte apesar de constarem desta matriz — a edição de
documentos e o chat, justamente os dois recursos mais visíveis de uma substituição do
Microsoft 365, não tinham nenhum teste funcional até 2026-08-12.
