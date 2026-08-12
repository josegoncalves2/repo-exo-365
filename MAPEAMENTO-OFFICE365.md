# Mapeamento Office 365 → eXo Platform Community 7.2.1

Documento que define **o que precisa estar ativo e comprovado** para que a implantação
seja considerada uma substituição da suíte Microsoft 365. Cada linha coberta gera um
teste correspondente em `tests/` sob a **dupla abordagem** (A: máquina/API · B: usuário
final em navegador real).

> Legenda de cobertura:
> **PLENA** — recurso equivalente, nativo e testável ·
> **PARCIAL** — equivalente com limitações a documentar ·
> **AUSENTE** — não existe no eXo Community; exige solução externa.

---

## 1. Colaboração e conteúdo

| Office 365 | eXo Community 7.2.1 | Cobertura | Teste |
|---|---|---|---|
| SharePoint / Teams (sites de equipe) | **Spaces** (espaços com membros, papéis, permissões) | PLENA | T-01 |
| OneDrive / SharePoint Docs | **Documents / Drive** (pessoal + do espaço, versionamento, lixeira) | PLENA | T-02 |
| Word / Excel / PowerPoint Online | **ONLYOFFICE DocumentServer 9.4** integrado (edição colaborativa simultânea no navegador) | PLENA | T-03 |
| OneNote | **Notes / Wiki** (páginas hierárquicas, versionamento, editor rico) | PLENA | T-04 |
| Planner / To Do | **Tasks** (tarefas, projetos, kanban, prazos, responsáveis) | PLENA | T-05 |
| Yammer / Viva Engage | **Activity Stream** (feed social, curtidas, comentários, menções) | PLENA | T-06 |
| Delve / busca corporativa | **Unified Search** sobre Elasticsearch 8.18 (documentos, pessoas, espaços, atividades) | PLENA | T-07 |
| Microsoft Forms | **Formulários** via app de conteúdo / enquetes no feed | PARCIAL | T-13 |

## 2. Comunicação

| Office 365 | eXo Community 7.2.1 | Cobertura | Teste |
|---|---|---|---|
| Teams (chat 1:1 e em grupo) | **eXo Chat** (mensagens diretas e por espaço, presença, anexos) | PLENA | T-08 |
| Teams (reuniões por vídeo) | Não nativo — integração externa (Jitsi/BBB) via add-on | PARCIAL | — |
| Outlook (calendário) | **Agenda** (eventos, convites, recorrência, disponibilidade, iCal) | PLENA | T-09 |
| Outlook (caixa postal / Exchange) | **AUSENTE** — o eXo não é servidor de e-mail; apenas envia notificações via SMTP | AUSENTE | T-10 |
| Notificações por e-mail | **Notification Service** (imediata, resumo diário/semanal, por canal) | PLENA | T-10 |

## 3. Pessoas e administração

| Office 365 | eXo Community 7.2.1 | Cobertura | Teste |
|---|---|---|---|
| Entra ID / AD (contas) | **Organization/IDM** + conector **LDAP/AD** nativo | PLENA | T-11 |
| Perfis / cartão de contato | **Perfil de usuário** (foto, cargo, contato, experiência) | PLENA | T-11 |
| Admin Center | **Administração** (usuários, grupos, papéis, permissões, portal, branding) | PLENA | T-12 |
| Viva Insights / gamificação | **Gamification / Kudos / Reward Wallet** | PLENA | T-13 |
| Power Automate | Não nativo — automação via REST API + webhooks externos | PARCIAL | — |
| SSO corporativo | SAML/OIDC/CAS via add-on (não incluso na Community por padrão) | PARCIAL | — |

## 4. Lacunas conhecidas — registro formal

Estes pontos **não** são cobertos pelo eXo Community e precisam de decisão à parte:

1. **Servidor de e-mail (Exchange/Outlook).** O eXo apenas *envia* via SMTP. Caixas
   postais, IMAP/POP3 e webmail exigem produto separado (Mailu, Mailcow, Zimbra).
2. **Videoconferência nativa.** Requer add-on de terceiros (Jitsi Meet, BigBlueButton).
3. **SSO SAML/OIDC.** Disponível como add-on, não habilitado por padrão na Community.
4. **Power BI / Power Apps.** Sem equivalente; exige ferramenta dedicada (Metabase, Grafana).

---

## 5. Matriz de testes

Cada teste é executado por **dois caminhos independentes** e só é aprovado se ambos passarem.

| ID | Funcionalidade | Abordagem A (máquina) | Abordagem B (usuário final, navegador real) |
|---|---|---|---|
| T-00 | Infraestrutura | healthcheck dos 6 serviços, versão da imagem, conectividade BD/ES | página inicial carrega e renderiza no navegador |
| T-01 | Espaços | REST cria/lê espaço e membros | usuário cria espaço pela UI, convida e o convidado vê |
| T-02 | Documentos | upload via REST/WebDAV, download e verificação de checksum | usuário arrasta arquivo na UI, vê na lista, baixa e abre |
| T-03 | Edição ONLYOFFICE | JWT válido, `/healthcheck`, sessão de edição aberta | usuário abre .docx, **digita texto**, salva e o conteúdo persiste |
| T-04 | Notes/Wiki | REST cria página e recupera conteúdo | usuário escreve página no editor rico e ela aparece publicada |
| T-05 | Tarefas | REST cria tarefa com prazo e responsável | usuário cria tarefa na UI, move de coluna, conclui |
| T-06 | Feed social | REST publica atividade e lista | usuário posta, outro usuário comenta e curte |
| T-07 | Busca | índice ES contém o documento; consulta retorna | usuário busca pelo nome na lupa e encontra o arquivo |
| T-08 | Chat | API do chat entrega mensagem | dois usuários trocam mensagem e ambos veem |
| T-09 | Agenda | REST cria evento; exportação iCal válida | usuário cria evento na UI e ele aparece no calendário |
| T-10 | E-mail | SMTP aceita e Mailpit registra a mensagem | usuário dispara convite e o e-mail é lido no Mailpit |
| T-11 | Usuários | REST cria usuário e autentica | novo usuário faz login real e edita o próprio perfil |
| T-12 | Administração | REST lê grupos/permissões | admin acessa o painel e altera uma configuração |
| T-13 | Gamificação | REST lê pontos/badges | usuário recebe kudos e o placar reflete |

**Critério de reprovação:** um teste que apenas confirme HTTP 200 sem exercer a função
real do usuário é considerado **inválido** e não conta como aprovação.
