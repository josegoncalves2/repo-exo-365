# Conector Cloud Drive — Nextcloud/WebDAV (`br.pmo.nuvem`)

Conector de armazenamento em nuvem **escrito do zero** para esta instalação,
para o item do briefing "Conectores Pré-instalados com Aplicações Externas".
Google Drive e OneDrive já existem como add-ons; **Nextcloud é o ausente, e é a
opção open-source** (preferência do operador).

    ./construir.sh

## O que está pronto (medido, não prometido)

| Camada | O que faz | Onde se prova |
|---|---|---|
| **Núcleo** (`br.pmo.nuvem`) | OAuth2 authorization-code + refresh, cliente WebDAV (PROPFIND/GET/PUT/MKCOL/MOVE/DELETE), modelo de arquivo, normalização de caminho, cofre de tokens, política de conflito | No host, com `javac`/`java`, **81 asseverações, 0 falhas** |
| **Adaptador** (`br.pmo.nuvem.exo`) | `NextcloudDriveConnector` (herda `CloudDriveConnector`), `NextcloudProvider`, `NextcloudUser`, `NextcloudDrive` (herda `JCRLocalCloudDrive`) | Compila dentro da imagem contra os 567 jars reais; `target/nuvem.jar` |

## Onde aparece na interface (e onde NÃO aparece ainda)

O Cloud Drive da eXo é fornecido pelo `cloud-drive-connectors.war`, **já
deployado** (visto em `/opt/exo/webapps`). A UI de conectar drives lê os
provedores registrados em `CloudDriveService` de forma **dinâmica**: ao montar
este jar e configurar credenciais, **"Nextcloud" passa a aparecer como opção no
diálogo "Conectar um drive"** (Documentos → pasta raiz → opção de nuvem) — com
ícone e botão da própria UI oficial, sem escrever uma tela nova.

O que falta para isso acontecer **não é código, é deploy** — e não é decisão
minha:

1. Embarcar `target/nuvem.jar` em `/opt/exo/lib/` (bind mount no
   `docker-compose.yml` — **já acrescentado em 2026-08-27** como
   `zz-nuvem.jar`, espelhando `zz-dlp-br.jar`) e reiniciar o `exo-app`.
   **Não tenho autorização para reiniciar** (ordem expressa: não reconstruir,
   não reiniciar).
2. Configurar credenciais em `conf/exo.properties` (nunca chumbadas; o bloco
   `exo.nuvem.nextcloud.*` **já está no arquivo**):
   ```
   exo.nuvem.nextcloud.server-url   = nuvem.pmo.gov.br
   exo.nuvem.nextcloud.schema       = https
   exo.nuvem.nextcloud.client-id    = <app OAuth2 criado no Nextcloud>
   exo.nuvem.nextcloud.client-secret= <segredo do app>
   exo.nuvem.nextcloud.disable      = false
   ```
   **`disable=false` é o padrão** (decisão alinhada ao operador: "implemente
   conectores mesmo sem destino conectado") — com isso o item **"Nextcloud"
   aparece no diálogo "Conectar um drive"** assim que o jar for montado e o
   `exo-app` reiniciado, mesmo sem servidor configurado. Tentar conectar sem
   `server-url`/`client-id` responde **erro claro** ("não configurado") — nada
   quebra e nada fica silencioso.

> **Segurança de boot (importante):** o construtor do `NextcloudDriveConnector`
> **nunca valida nem lança** — espelha o `GoogleDriveConnector` nativo, cujo
> construtor só chama `super()`. A validação de `server-url`/`client-id` vive em
> `garantirClientes()`, chamada apenas no `authenticate`/`createDrive`/`loadDrive`
> (uso real, com o provedor habilitado). O kernel instancia plugins de
> `external-component-plugins` **antes** de consultar `isDisabled()`; se o
> construtor lançasse com config vazia, o boot do `CloudDriveService` quebraria
> mesmo com `disable=true`. Foi exatamente o que o bytecode do `addPlugin`
> provou (`isDisabled()` → pula registro, sem tocar em `getProvider()`).

## O que este núcleo NÃO é

- **Não é provisionamento.** Não sobe servidor Nextcloud, não cria usuário, não
  semeia pasta. Fala com o Nextcloud que JÁ EXISTE na rede da prefeitura.
- **Não tem sincronização incremental** por change-id do Nextcloud: o primeiro
  ciclo sincroniza a árvore inteira via PROPFIND, que é correto (nunca perde
  dado) e só é menos eficiente em acervo grande.
- **Não escreve a tela de administração do provedor.** A UI existente do
  `cloud-drive-connectors.war` renderiza provedores dinamicamente; uma página
  de administração própria (nó em "Segurança", como `transfer-rules`) fica como
  melhoria futura se o operador quiser configuração pela interface em vez de
  properties.

## As decisões que sustentam o resto

**1. O caminho remoto nunca é confiável.** PROPFIND responde caminhos que vão
virar nós no JCR. `CaminhoNuvem.de()` recusa `..`, barra dupla, barra
invertida, byte nulo, caractere de controle, espaço e espaço invisível — em um
único ponto de entrada, para nenhum consumidor esquecer de validar. Caminho
inválido LANÇA exceção; nunca "conserta" por suposição.

**2. Falha é distinguível de sucesso.** 401/403 → `SemPermissaoException`; 404 →
`NaoEncontradoException`; 207 malformado → `WebDavException`. Nenhum caminho
devolve "lista vazia" quando o servidor disse não.

**3. Conflito nunca é silencioso.** Os dois lados mudaram → `CONFLITO`, grava
cópia com sufixo de data e registra para decisão humana. Em portal de órgão
público, perder dado por algoritmo é processo trabalhista em potencial.

**4. XXE desabilitado.** Servidor de nuvem comprometido responde XML. O parser
usa `FEATURE_SECURE_PROCESSING`, `disallow-doctype-decl` e entidades externas
off — provado com um DOCTYPE apontando para `file:///etc/passwd`.

**5. Token nunca aparece.** Nem em URL (o segredo de cliente vai no corpo do
POST, como manda a RFC 6749), nem em `toString()`, nem em log (só o hash de 8
caracteres). O state anti-CSRF é gerado com `SecureRandom`, conferido uma vez e
expirado em 10 minutos.

## Prova

`./construir.sh` valida o XML, compila o núcleo com `-Xlint:all -Werror`, roda
**81 asseverações** (sai 1 se qualquer uma falhar), compila o adaptador dentro
do container descartável e confere o conteúdo do jar. Falha = build aborta.

O que as provas cobrem:

- **Caminho**: aceita o legítimo; recusa `../etc/passwd`, barra dupla,
  `\` de outro sistema, NUL, controle, espaço e espaço invisível.
- **Conflito**: nenhum lado mudou → INALTERADO; um lado → vence o que mudou;
  os dois → CONFLITO; etag ausente registrado (nunca escondido).
- **OAuth2**: URL de autorização sem segredo na query; troca de código;
  refresh; `invalid_grant` → `TokenExpiradoException`.
- **WebDAV** (contra `com.sun.net.httpserver` local, não internet): PROPFIND
  modela arquivo e pasta com etag/tamanho/mime; 401/404/malformado/XXE todos
  recusados; PUT+GET ida e volta.

## Verificação de uso real (navegador, mouse e teclado)

O `construir.sh` é portão de compilação, **não é aceite**. O aceite só vale
depois do deploy (jar no `exo-app` + credenciais) e feito à mão:

1. Entrar em `https://192.168.1.59` com uma conta comum.
2. Ir a **Documentos**, abrir o diálogo de conectar nuvem e conferir que
   **Nextcloud** aparece na lista de provedores.
3. Autorizar no servidor Nextcloud (fluxo OAuth2) e conferir que a pasta do
   drive é criada no portal.
4. Editar um arquivo no portal, sincronizar e conferir que o arquivo chega ao
   Nextcloud sem conflito silencioso.
