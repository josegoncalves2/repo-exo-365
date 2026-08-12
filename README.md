# eXo Platform Community 7.2.1 — Suíte colaborativa

Substituição open source da suíte Microsoft 365, provisionada em
`192.168.1.59` (`pmoexo`), diretório `/opt/projetos/exo`.
**O servidor é dedicado exclusivamente a esta suíte.**

| Documento | Conteúdo |
|---|---|
| [`AUDIT.md`](AUDIT.md) | **Trilha de auditoria completa** — cada passo, decisão, defeito e teste |
| [`MAPEAMENTO-OFFICE365.md`](MAPEAMENTO-OFFICE365.md) | O que cada recurso do Microsoft 365 corresponde no eXo, e as lacunas |
| `evidence/` | Saída bruta de toda execução e capturas de tela dos testes |

---

## Instalação do zero (a partir deste repositório)

```bash
git clone https://github.com/josegoncalves2/repo-exo-365.git
cd repo-exo-365

# 1. Gera .env com segredos aleatórios (nenhum segredo é versionado)
./scripts/gerar-segredos.sh 192.168.1.59      # use seu IP/hostname

# 2. Reconstrói tudo do zero, com log limpo desde o primeiro início
export SUDO_ASKPASS=/caminho/para/askpass.sh   # ver "Requisito de sudo" abaixo
./scripts/reconstruir-do-zero.sh

# 3. Abra http://SEU_IP/ e crie a conta administrativa na tela inicial
```

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
> Caixas postais (equivalente ao Exchange/Outlook) exigem produto separado.
> Ver `MAPEAMENTO-OFFICE365.md`, seção "Lacunas conhecidas".

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
