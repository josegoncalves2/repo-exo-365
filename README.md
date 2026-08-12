# eXo Platform Community 7.2.1 — Suíte colaborativa

Substituição open source da suíte Microsoft Office 365, provisionada em
`192.168.1.59` (`pmoexo`), diretório `/opt/projetos/exo`.

| Documento | Conteúdo |
|---|---|
| [`AUDIT.md`](AUDIT.md) | **Trilha de auditoria completa** — cada passo, decisão, defeito e teste |
| [`MAPEAMENTO-OFFICE365.md`](MAPEAMENTO-OFFICE365.md) | O que cada recurso do Office 365 corresponde no eXo, e as lacunas |
| `evidence/` | Saída bruta de toda execução e capturas de tela dos testes |

---

## Instalação do zero (a partir deste repositório)

```bash
git clone https://github.com/josegoncalves2/repo-exo-365.git
cd repo-exo-365

# 1. Gera .env com segredos aleatórios (nenhum segredo é versionado)
./scripts/gerar-segredos.sh 192.168.1.59      # use seu IP/hostname

# 2. Sobe a stack SEM pico de memória (um serviço por vez, com trava)
./scripts/subir-seguro.sh

# 3. Provisiona o chat (Matrix/Synapse) e integra ao eXo
./scripts/setup-matrix.sh
docker compose up -d --force-recreate exo

# 4. Abra http://SEU_IP/ e crie a conta administrativa na tela inicial
```

Depois, para validar tudo:

```bash
./scripts/preparar-testes.sh    # cria tests/.venv + Chromium (não versionados)
./tests/run_all.sh              # suíte completa; registra em AUDIT.md
```

> **Nenhum segredo está neste repositório.** `.env` e `conf/exo.properties`
> são gerados na instalação e estão no `.gitignore`. Os modelos versionados
> (`.env.example`, `conf/exo.properties.example`) contêm apenas placeholders.

---

## Acesso

| Serviço | URL | Observação |
|---|---|---|
| **Portal eXo** | http://192.168.1.59/ | Ponto de entrada dos usuários |
| **Mailpit** (inspeção de e-mail) | http://192.168.1.59:8025/ | Captura tudo que o eXo envia |

Credenciais administrativas e senhas de banco estão em `.env` (permissão `600`).
Todos os segredos foram gerados com `openssl rand -hex 32` — nenhum valor de exemplo
do repositório oficial foi mantido.

---

## Arquitetura

```
                    Navegador do usuário
                            │  :80
                    ┌───────▼────────┐
                    │  exo-web       │  nginx 1.30 — proxy reverso
                    └───┬────────┬───┘
              /         │        │      /doc/ /coauthoring /web-apps …
      ┌─────────────────▼──┐  ┌──▼──────────────────┐
      │ exo-app            │  │ onlyoffice          │
      │ eXo 7.2.1 / JDK 21 │◄─┤ DocumentServer 9.4  │
      │ Tomcat             │  │ (edição colaborativa)│
      └──┬─────────┬───────┘  └─────────────────────┘
         │         │       │
  ┌──────▼───┐ ┌───▼────┐ ┌▼──────────┐
  │ exo-mysql│ │ exo-es │ │exo-mailpit│
  │  8.4.9   │ │ 8.18.8 │ │   SMTP    │
  └──────────┘ └────────┘ └───────────┘
```

Rede Docker `exo_net`. **Apenas as portas 80 e 8025 são publicadas** — MySQL,
Elasticsearch e ONLYOFFICE não são acessíveis de fora do host.

---

## Operação

```bash
cd /opt/projetos/exo

docker compose ps                  # estado e saúde dos 6 serviços
docker compose logs -f exo         # acompanhar a aplicação
docker compose restart exo         # reiniciar apenas o eXo
docker compose down                # parar tudo (dados preservados nos volumes)
docker compose up -d                # subir tudo
```

**O primeiro boot leva de 10 a 20 minutos** (Liquibase cria ~500 tabelas e o Tomcat
implanta ~70 webapps). Reinícios seguintes levam de 3 a 5 minutos. Enquanto o eXo
não termina, o proxy responde **502** — isso é esperado, não é falha.

### Testes

```bash
./tests/run_all.sh                 # suíte completa, registra em AUDIT.md
./tests/run_all.sh tests/test_00_infra.py    # apenas uma suíte
```

Cada execução grava evidência em `evidence/` e acrescenta uma entrada em `AUDIT.md`
automaticamente.

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

e recrie apenas a aplicação:

```bash
docker compose up -d --force-recreate exo
```

> **Lembrete:** o eXo **não é servidor de e-mail**. Ele apenas envia notificações.
> Caixas postais (equivalente ao Exchange/Outlook) exigem produto separado.
> Ver `MAPEAMENTO-OFFICE365.md`, seção "Lacunas conhecidas".

---

## Backup

O estado persistente vive em volumes Docker:

| Volume | Conteúdo |
|---|---|
| `exo_mysql_data` | Banco (usuários, espaços, atividades, metadados) |
| `exo_exo_data` | Binários: documentos, imagens, anexos |
| `exo_exo_codec` | **Chaves de criptografia — sem elas o banco não é legível** |
| `exo_search_data` | Índices do Elasticsearch (reconstruíveis) |
| `exo_onlyoffice_data` | Estado do DocumentServer |

`exo_exo_codec` e `exo_mysql_data` precisam ser copiados **juntos**: o banco guarda
valores cifrados com a chave do codec.

---

## Capacidade — ressalva registrada

O host tem **2 vCPU e 7.941 MB de RAM**, abaixo do confortável para esta stack.
Os limites por container somam 6.756 MB de teto contra ~6.353 MB disponíveis; como
`mem_limit` é teto e não reserva, o consumo simultâneo real fica em torno de 5,5 GB,
com 8 GB de swap e `vm.swappiness=10` como rede de segurança.

**Recomendação: ampliar a VM para 16 GB de RAM e 4 vCPU.** Com isso é possível elevar
`EXO_JVM_SIZE_MAX` para 4g e o heap do Elasticsearch para 2g, que são os valores do
compose oficial.
