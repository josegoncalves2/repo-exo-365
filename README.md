# eXo Platform Community 7.2.1 — Suíte colaborativa

Substituição open source da suíte Microsoft 365, provisionada em
`192.168.1.59` (`pmoexo`), diretório `/opt/projetos/exo`.
**O servidor é dedicado exclusivamente a esta suíte.**

---

## Instalação do zero (a partir deste repositório)

Um comando. O resto é conferência.

```bash
git clone https://github.com/josegoncalves2/repo-exo-365.git /opt/projetos/exo
cd /opt/projetos/exo
./scripts/instalar.sh 192.168.1.59        # use o IP/hostname do servidor novo
```

`scripts/instalar.sh` faz, nesta ordem, verificando antes de cada passo:

| Passo | O que faz | Se já existir |
|---|---|---|
| 1 | confere docker, compose v2, python3, openssl, sudo e memória (≥ 8 GB) | aborta listando o que falta |
| 2 | gera `.env` com segredos aleatórios e `conf/exo.properties` | **preserva** — sobrescrever invalidaria as senhas já gravadas no banco |
| 3 | emite os certificados TLS da CA interna (MySQL, portal, Jitsi) | preserva |
| 4 | sobe os 13 serviços na ordem, com healthcheck | se `./data` tem conteúdo, apenas inicia — **nunca reconstrói sobre um banco** |
| 5 | lê o gateway real da rede `exo_net`, aponta o nginx e instala as duas unidades systemd | não reescreve unidade idêntica |
| 6 | provisiona a hierarquia de `conf/estrutura/*.json` | reaplica sem duplicar (idempotente) |
| 7 | confere portal, `/estrutura/`, modelo de CSV e saúde dos containers | — |

Rodar de novo num servidor já instalado é seguro: cada passo detecta o que
existe e não mexe.

### O que o clone reproduz — e o que não reproduz

**Reproduz:** a stack inteira (13 containers), os segredos, os certificados, o
proxy, as duas unidades systemd, a interface `/estrutura/` e a hierarquia
organizacional descrita em `conf/estrutura/*.json`.

**Não reproduz — e nenhum repositório poderia:** o *conteúdo* já produzido —
publicações, arquivos, mensagens do chat, contas criadas à mão. Isso é dado de
execução, vive em `./data` e no MySQL, e está fora do git de propósito (são
gigabytes, e conteria senhas). Para levá-lo ao servidor novo, use
`scripts/backup.sh` no antigo e restaure lá.

**Fora do git por segurança:** `.env`, `conf/exo.properties` e as chaves
privadas (`conf/mysql-certs/`, `conf/portal-certs/`, `conf/jitsi-certs/`) — todos
regenerados pelo passo 2 e 3.

### Serviços do host (não são containers)

| Unidade | Modelo versionado | Papel |
|---|---|---|
| `exo.service` | `deploy/exo.service` | sobe a stack no boot, ordenada, sem estourar memória |
| `exo-estrutura.service` | `deploy/exo-estrutura.service` | interface `/estrutura/` no host, com disco **somente-leitura** |

Os modelos usam `@RAIZ@`, `@USUARIO@` e `@GATEWAY@`, substituídos na instalação.
Edite o modelo e rode `./scripts/instalar.sh` de novo — nunca a cópia em
`/etc/systemd/system`.

Depois, para validar tudo:

```bash
./scripts/preparar-testes.sh    # cria tests/.venv + Chromium (não versionados)
./tests/run_all.sh              # suíte completa; registra em AUDIT.md
./scripts/verificar-logs.sh     # auditoria de erros/warnings em TODAS as fontes
```

### Requisito de sudo

`reconstruir-do-zero.sh` precisa de `root` para apagar e recriar `./data/` com os
donos corretos (UIDs de dentro dos containers). Em execução **não interativa** o
`sudo` comum falha em silêncio — e o script então "reconstruiria" sem apagar nada.
Por isso ele **exige** elevação sem interação e aborta se não a tiver:

```bash
printf '#!/bin/sh\nprintf %%s "SUA_SENHA"\n' > /tmp/ap.sh
chmod 700 /tmp/ap.sh && export SUDO_ASKPASS=/tmp/ap.sh
```

(ou configure `NOPASSWD` para o usuário no `sudoers`.)

> **Nenhum segredo está neste repositório.** `.env`, `conf/exo.properties` e
> `conf/mysql-certs/` são gerados na instalação e estão no `.gitignore`.

---

## Acesso

| Serviço | URL | Observação |
|---|---|---|
| **Portal eXo** | http://192.168.1.59/ | Ponto de entrada dos usuários |
| **Mailpit** (inspeção de e-mail) | http://192.168.1.59:8025/ | Captura tudo que o eXo envia |

Credenciais administrativas e senhas de banco estão em `.env` (permissão `600`).
Todos os segredos são gerados com `openssl rand` — nenhum valor de exemplo do
repositório oficial é mantido.

---

## Arquitetura — 8 serviços

```
                    Navegador do usuário
                            │  :80
                    ┌───────▼────────┐
                    │  exo-web       │  nginx 1.30 — proxy reverso
                    └──┬────┬────┬───┘
          /            │    │    │  /_matrix    /doc /coauthoring /web-apps
     ┌────────────────▼─┐ ┌▼──────────────┐ ┌▼────────────────────┐
     │ exo-app          │ │ exo-synapse   │ │ onlyoffice          │
     │ eXo 7.2.1/JDK 21 │ │ Synapse 1.158 │ │ DocumentServer 9.4  │
     │ Tomcat, 48 webapps│ │ (chat Matrix) │ │ (edição colaborativa)│
     └──┬────┬────┬─────┘ └──────┬────────┘ └─────────────────────┘
        │    │    │              │
 ┌──────▼─┐ ┌▼─────┐ ┌───────▼─┐ ┌▼──────────────┐
 │exo-mysql│ │exo-es│ │exo-mailpit│ │exo-synapse-db│
 │  8.4.9  │ │8.18.8│ │   SMTP    │ │ PostgreSQL 16│
 └─────────┘ └──────┘ └───────────┘ └──────────────┘
```

Rede Docker `exo_net`. **Apenas as portas 80 e 8025 são publicadas** — MySQL,
Elasticsearch, Synapse e ONLYOFFICE não são acessíveis de fora do host.

### Persistência

Todo o estado vive em **bind mounts** sob `./data/`, e não em volumes nomeados.
Isso é deliberado: em 2026-08-12 os dados foram destruídos por um
`docker compose down -v`. Com bind mounts esse comando não apaga nada.

| Diretório | Conteúdo |
|---|---|
| `data/mysql` | Banco (usuários, espaços, atividades, metadados) |
| `data/exo` | Binários: documentos, imagens, anexos |
| **`data/exo-codec`** | **Chaves de criptografia — sem elas o banco não é legível** |
| `data/elasticsearch` | Índices de busca (reconstruíveis) |
| `data/synapse`, `data/synapse-db` | Chat: salas, mensagens, mídia |
| `data/onlyoffice` | Estado do DocumentServer |

`data/exo-codec` e `data/mysql` precisam ser copiados **juntos**: o banco guarda
valores cifrados com a chave do codec.

---

## Add-ons oficiais — o que está instalado e por quê

Nada de add-on entra na imagem "na mão". A lista, a versão, o `sha256`, a
justificativa e a necessidade de `--no-compat` moram em
**`conf/addons/manifesto.json`** (fonte única); quem lê e instala é
`scripts/addons.py`, usando o **Add-on Manager oficial da eXo**. O Dockerfile não
cita o nome de nenhum add-on.

```bash
./scripts/addons.py resolver    # o catálogo oficial propõe versão por add-on
./scripts/addons.py conferir    # o manifesto ainda bate com o catálogo?
./scripts/addons.py baixar      # preenche conf/addons/cache/ e confere o sha256
./scripts/addons.py listar      # o que está instalado na imagem
```

| Item do briefing | Add-on | Versão |
|---|---|---|
| Chat integrado | Matrix/Synapse (já na imagem) | — |
| Videoconferência | `exo-jitsi` | 7.2.1 |
| Edição de documentos | OnlyOffice (já na imagem) | — |
| Chamados dentro do portal | `exo-glpi-integration` | 7.2.0 |
| **DLP — add-on nativo** | `exo-dlp` | 7.2.1 |
| **DLP — motor proprio (deteccao, acoes, descoberta)** | `exo-dlp` (container, `dlp/`) | 1.1 |
| **DLP — console de administracao** | `pmo-dlp-console` (portlet, `extensao/dlp-console/`) | 1.0 |
| **2FA por grupo/zona** | `exo-multifactor-authentication` | 7.2.1 |
| Antimalware / anti-força-bruta | `exo-anti-malware`, `exo-anti-bruteforce` | 7.2.1 |
| SSO federado / API por token | `exo-saml`, `exo-jwt-authentication` | 7.2.1 / 7.2.0 |
| **Gerenciador de Add-ons** | `exo-addons-manager` | 7.2.1-exo |
| **Gerenciador de Migração** | `exo-data-upgrade` | 7.2.1 |
| Correio, agenda externa, drives externos | `exo-mail-integration`, `exo-agenda-connectors`, `exo-cloud-drive-connectors` | 7.2.1 |
| Tradução automática | `exo-automatic-translation` | 7.2.1 |
| **Assistente de IA** | `meeds-ai` | 7.2.1-exo |

São **15 entradas no manifesto** e **14 arquivos em `/opt/exo/addons/statuses/`** — a
diferença é o `exo-addons-manager`, que não gera `.status` porque é semeado à mão no
build, antes de existir gerenciador para registrá-lo. Os dois números estão certos.

Todos são **AGPLv3 ou LGPLv3**, com `mustAcceptLicense=false`. Nenhum é pago.
Nenhum exige contrato. O que impedia instalá-los era só o fato de o comando `addon`
não vir na imagem — e ele próprio é um add-on do catálogo.

### Três portões que o build não deixa passar

1. **`sha256` selado.** O manifesto fixa a soma de cada zip. Divergência reprova o
   build. Só `addons.py baixar --selar` grava soma nova, e ele mostra o antes e o depois.
2. **`javax.servlet`.** A eXo 7.x roda em Tomcat 10 (`jakarta.servlet`). Add-on
   compilado contra `javax.servlet` **derruba o portal inteiro**, não só a si mesmo —
   aconteceu em 2026-08-26 (AUDIT [144]). O catálogo *não* protege: `exo-exchange-extension`
   1.3.1 declara compatibilidade `[4.4,)`, que inclui a 7.2.1, e é `javax`. Por isso
   `addons.py` **abre cada zip e mede o binário** em vez de acreditar no metadado.
3. **Distribuição.** A imagem se identifica como `exo_community`; parte do catálogo
   declara só `community,enterprise`. É rotulagem, não licença. O manifesto marca
   `no_compat` add-on a add-on, e `conferir` reprova se divergir do catálogo — o flag
   nunca é global.

### O que NÃO entrou, e por quê

| Pedido | Situação |
|---|---|
| 2FA por FIDO / OIDC | `mfa-fido` e `mfa-oidc` (1.0.1) são `javax.servlet`. **Não existe build compatível com 7.2.** Fica só OTP (app autenticador). |
| Add-on Exchange dedicado | `exo-exchange-extension` 1.3.1 é `javax.servlet`. A via viável é `exo-agenda-connectors` 7.2.1 (`exo.agenda.exchange.connector.enabled`), nativo da 7.2.1. |
| CalDAV | `exo-caldav-integration` 7.2.1 responde HTTP 404 no repositório oficial. |
| Suporte com SLA, manutenção pela eXo, serviços sob demanda | **Não é software — é contrato comercial com a eXo SAS.** Nenhum script cria isso. O que se constrói no lugar é capacidade interna: pipeline de patches, backup testado e observabilidade. |

### Instalados e DESLIGADOS de propósito

DLP nasce sem palavra-chave, 2FA sem grupo protegido, IA e tradução automática sem
provedor. Não é entrega pela metade — é que cada um desses, ligado no escuro, causa
dano: DLP com palavra chutada tira do ar documento legítimo; 2FA ligado em
`/platform/administrators` antes de os administradores cadastrarem o autenticador
**tranca o portal**. O motivo de cada um está escrito ao lado da chave em
`conf/exo.properties.example`.

> **IA — decisão do operador, 2026-08-25:** *nenhum LLM local, em hipótese alguma.*
> Não há e não haverá Ollama nesta stack. O `meeds-ai` suporta provedor externo
> (Anthropic, OpenAI, Mistral, xAI), cadastrado **na tela** — provedor e chave ficam no
> banco. Não existe, e não deve passar a existir, chave de API neste repositório.
> O RAG usa o Elasticsearch que a stack já tem; nenhum serviço novo.

---

## Operação

```bash
cd /opt/projetos/exo

docker compose ps                  # estado e saúde dos 8 serviços
docker compose logs -f exo         # acompanhar a aplicação
docker compose restart exo         # reiniciar apenas o eXo
docker compose down                # parar tudo (dados preservados em ./data)
docker compose up -d               # subir tudo
./scripts/verificar-logs.sh        # 0 erros / 0 warnings? (portão de qualidade)
```

**O primeiro boot leva de 10 a 20 minutos** (Liquibase cria as tabelas e o Tomcat
implanta 48 webapps). Reinícios seguintes levam de 3 a 5 minutos.

> O proxy `exo-web` só sobe **depois** que o eXo fica saudável
> (`depends_on: service_healthy`). Isso é intencional: evita dezenas de
> `connect() failed (111: Connection refused)` no log durante o boot.

### Testes

```bash
./tests/run_all.sh                            # suíte completa
./tests/run_all.sh tests/test_03_onlyoffice_edicao.py   # apenas uma suíte
```

Cada execução grava evidência em `evidence/` e acrescenta uma entrada em `AUDIT.md`.

---

## Política de logs: zero erros, zero warnings

É exigência do projeto que **nenhum** log — do Linux base ou do projeto —
apresente erro ou warning. `./scripts/verificar-logs.sh` mede isso em todas as
fontes (systemd, journal, dmesg e os 8 containers) e devolve código de saída
diferente de zero se houver qualquer ocorrência, servindo como portão em automação.

Correções aplicadas para atingir esse estado estão detalhadas no `AUDIT.md`
(entradas [040] a [044]); as principais:

- **MySQL:** PKI de 2 níveis (a CA entregue ao servidor não é autoassinada),
  `pid-file` em diretório `750`, `innodb_redo_log_capacity` no lugar do parâmetro
  depreciado — e **pré-inicialização do datadir** em container descartável, porque
  a criação do banco emite avisos que ficariam para sempre no log de produção.
- **Elasticsearch:** `discovery.type=single-node` no lugar de
  `cluster.initial_master_nodes`; `xpack.inference.enabled=false`.
- **PostgreSQL:** imagem Debian (a alpine não traz locales) e autenticação
  `scram-sha-256` no lugar de `trust`.
- **nginx:** `proxy_max_temp_file_size 0` e `depends_on: service_healthy`.
- **eXo/Tomcat:** `web.xml` do `webdav` com OPTIONS explicitamente coberto.
- **Host:** unidades órfãs removidas, `/boot` remontado (estava **desmontado**),
  GlusterFS/rpcbind desativados (sem apagar dados), módulos de kernel sem
  hardware correspondente na lista negra.

---

## Trocar o Mailpit por um SMTP corporativo

O Mailpit **captura** os e-mails para permitir a comprovação nos testes; ele não
entrega para caixas reais. Para usar um servidor real, edite `.env`:

```ini
EXO_MAIL_FROM=noreply@suaempresa.com.br
EXO_MAIL_SMTP_HOST=smtp.suaempresa.com.br
EXO_MAIL_SMTP_PORT=587
EXO_MAIL_SMTP_STARTTLS=true
EXO_MAIL_SMTP_USERNAME=usuario
EXO_MAIL_SMTP_PASSWORD=senha
```

e recrie apenas a aplicação: `docker compose up -d --force-recreate exo`

> **Lembrete:** o eXo **não é servidor de e-mail**. Ele apenas envia notificações.
> Caixas postais (equivalente ao Exchange) exigem produto separado (Mailu, Mailcow,
> Zimbra). O add-on `exo-mail-integration` (instalado, desligado) não muda isso: ele é
> um **cliente** IMAP dentro do portal, que lê uma caixa que já exista em outro lugar.

---

## Capacidade

VM com **4 vCPU e 9.945 MB de RAM**. O teto somado dos 8 containers é **7.680 MB**,
deixando ~2.265 MB para o sistema, o daemon Docker e o cache de página:

| Serviço | `mem_limit` | | Serviço | `mem_limit` |
|---|---|---|---|---|
| `exo` | 3072m | | `web` | 128m |
| `es` | 1792m | | `mailpit` | 128m |
| `onlyoffice` | 1280m | | `synapse` | 384m |
| `mysql` | 640m | | `synapse-db` | 256m |

> **Não aumentar sem verificar a RAM física do hipervisor.** Em três ocasiões
> (AUDIT [022], [025], [029]) o host Proxmox sofreu OOM e matou esta VM inteira.
> `mem_limit` é **teto**, não reserva.

## Estrutura organizacional (Secretaria / Divisão / Setor)

Provisiona a hierarquia no eXo: grupo, espaço, aninhamento, cadeia de
visibilidade, perfil (descrição, avatar, banner) e pessoas.

**A visibilidade desce, não sobe.** Quem está na Secretaria enxerga as Divisões
e os Setores; quem está na Divisão enxerga os Setores; quem está no Setor
enxerga apenas o Setor.

### Interface web

Faz parte da stack. Sobe com os demais serviços e é publicada pelo mesmo proxy
do portal, no mesmo TLS da CA interna — sem porta extra no host:

**https://192.168.1.59/estrutura/**

**Acesso restrito.** Só entra quem já está autenticado no portal **e** pertence
a `/platform/administrators`. Anônimo recebe 401; usuário comum autenticado
recebe 403 — inclusive na leitura do log. Não há senha a digitar: a execução
usa a própria sessão do administrador, então o servidor nunca vê nem guarda
credencial, e cada execução fica atribuída a quem a disparou (`operador:` no
log). As ações de escrita exigem o cabeçalho `X-Estrutura`, que requisição
cross-site não consegue enviar — proteção contra CSRF.

A URL do eXo é fixada pelo servidor (`EXO_URL` no compose) e não é aceita do
navegador: se viesse do pedido, qualquer um escolheria para qual host o
backend se conecta (SSRF).

```bash
docker compose up -d estrutura      # container exo-estrutura
docker compose restart estrutura    # aplica correções nos scripts (bind mount)
docker compose logs -f estrutura
```

Monta a árvore na tela (várias secretarias, cada uma com suas divisões e
setores, com nomenclatura própria em cada nível), aceita imagens de avatar e
banner, e traz os botões **Executar**, **Parar**, **Remover** e **Baixar JSON**,
com o log da execução ao vivo. A senha do administrador é digitada na tela,
fica só em memória e não é gravada em disco.

### Linha de comando

```bash
export EXO_URL=https://192.168.1.59 EXO_ADMIN_USER=root EXO_ADMIN_PASS=...

# árvore inteira, a partir do mesmo JSON que a interface web gera
./scripts/estrutura-organizacional.py --arquivo conf/estrutura/sitds.json
./scripts/estrutura-organizacional.py --arquivo conf/estrutura/sitds.json --remover --sim

# um nível por vez
./scripts/estrutura-organizacional.py --tipo secretaria --nome SITDS \
    --rotulo "Secretaria de Inovação" --gestores wilson.franca \
    --descricao "..." --banner conf/estrutura/img/SITDS-banner.png

# simulação: mostra tudo o que faria, sem gravar
./scripts/estrutura-organizacional.py --arquivo estrutura.json --dry-run
```

`--gestores` entra como manager do grupo do nível **e** do grupo técnico do
espaço — é o segundo que dá o poder de administrar, convidar e editar.
`--usuarios` entra como membro comum. Ambos aceitam CSV ou lista por vírgula.

### Rollback

Qualquer erro no meio da execução desfaz o que **aquele run** criou, na ordem
inversa (memberships, vínculos, espaços, grupos). O que já existia antes fica
intocado. O botão Parar tem o mesmo efeito.

### Imagens de perfil

```bash
./scripts/gerar-imagens-espaco.py "Setor de Tecnologia" ST conf/estrutura/img
```

Gera banner e avatar em PNG com cor estável derivada do nome, sem dependência
externa.

### Detalhe operacional

A entrada de quem **já estava** nos grupos só aparece nos espaços quando o
`QueueGroupSpaceBindingJob` do eXo roda — cron a cada 5 minutos. Conferir logo
depois de executar dá a falsa impressão de que faltou gente.

### Arquivos

| Arquivo | Papel |
|---|---|
| `scripts/exo_estrutura.py` | motor: cliente REST, provisionamento, rollback, remoção |
| `scripts/estrutura-organizacional.py` | CLI |
| `scripts/estrutura-web.py` | interface web |
| `scripts/gerar-imagens-espaco.py` | avatar e banner PNG |
| `conf/estrutura/*.json` | árvores versionadas |
| _(nenhum estado em disco)_ | a verdade é o banco do eXo; o diário de execução vai para `journalctl -u exo-estrutura` |
