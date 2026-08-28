# Permissoes em /opt/projetos/exo

## O padrao

| Alvo | Dono | Modo |
|---|---|---|
| fonte, `conf/`, `*.md` | quem edita a arvore (hoje `saexo`) | `644` |
| `construir.sh` e demais `*.sh` | idem | `755` |
| `target/` e subdiretorios | idem | `755` |
| artefatos `target/*.jar` | idem | `644` |

Regra unica, da qual tudo o mais decorre:
**o que o build escreve nasce do UID de quem invocou o build.**

Excecao unica: `data/`. Ver secao propria — normalizar `data/` quebra servico.

## Por que isto quebrou (corrigido em 2026-08-28)

Os sete `extensao/*/construir.sh` compilavam dentro de container com:

```sh
docker run --rm --user root -v "$(pwd):/src" ...
```

A imagem `exo-pmo:7.2.1-addons2` ja' roda como usuario `exo`
(`docker image inspect --format '{{.Config.User}}'`): o `--user root` era um
override explicito, nao um padrao herdado. Como o diretorio do projeto entra
montado, **todo arquivo que o container escrevia nascia `root:root`** dentro de
uma arvore que e' do `saexo`. O dono da arvore nao conseguia regravar nem
apagar o proprio artefato, e o build seguinte falhava ou deixava lixo.

Foram 7 jars e 54 caminhos em `target/` na raiz.

Correcao, uma linha em cada script:

```sh
docker run --rm --user "$(id -u):$(id -g)" -v "$(pwd):/src" ...
```

O usuario nao aparece escrito em lugar nenhum: o dono certo e' sempre quem
invoca. Um `saexo` chumbado voltaria a errar no dia em que outra conta buildar.

Verificado: os 7 jars reconstruidos assim sao **identicos entrada por entrada
(nome + CRC)** aos que o root produzia. A troca mexe em propriedade, nao em
artefato.

## A excecao: `data/`

**`data/` NAO segue este padrao e nao deve ser normalizado.**

Sao volumes montados nos containers. Cada servico roda com o UID que a sua
imagem define, e o kernel so' deixa o processo escrever se o dono do volume
bater com esse UID. `chown` ali **derruba o servico**, e o conteudo e' dado de
producao (banco, indice, salas).

Os nomes que o `/etc/passwd` do host mostra sao coincidencia de UID — nao ha'
relacao alguma com dnsmasq, dhcpcd ou D-Bus:

| Caminho | Dono observado | UID real |
|---|---|---|
| `data/elasticsearch` | `1000:root` | 1000 |
| `data/mysql`, `data/mysql-run`, `data/synapse-db` | `dnsmasq:*` | 999 |
| `data/exo-logs` | `dnsmasq:1001` | 999, gid 1001 |
| `data/jitsi` | `dhcpcd:saexo` | 100 |
| `data/onlyoffice` | `messagebus:messagebus` | 101 |

Consequencia pratica: **nunca rode `chown -R` na raiz do projeto** — ele
atravessa `data/`. Sempre feche o escopo em `target/`.

## Como conferir

Todos os comandos abaixo **devem sair vazios**. Qualquer linha impressa e' uma
divergencia real, com o caminho no fim da linha.

Area de build (o que este documento governa):

```sh
find /opt/projetos/exo/target /opt/projetos/exo/extensao/*/target \
     ! -user "$(id -un)" -printf '%u:%g %M %p\n'
```

Modos fora do padrao na area de build:

```sh
find /opt/projetos/exo/target /opt/projetos/exo/extensao/*/target \
     \( -type f ! -perm 644 -o -type d ! -perm 755 \) -printf '%M %p\n'
```

Arvore inteira exceto a excecao (pega tambem fonte e `conf/`):

```sh
find /opt/projetos/exo -path /opt/projetos/exo/data -prune -o \
     ! -user "$(id -un)" -printf '%u:%g %M %p\n'
```

Script de build sem bit de execucao — `transferencia/construir.sh` invoca
`./construir.sh` do `mfa-zona`, entao um `644` aqui e' falha de build:

```sh
find /opt/projetos/exo/extensao -name '*.sh' ! -perm -u+x -printf '%M %p\n'
```

Regressao da causa-raiz — nenhum script pode voltar a forcar root:

```sh
grep -n -- '--user root' /opt/projetos/exo/extensao/*/construir.sh
```

## Como corrigir quando divergir

Escopo fechado na area de build, nunca na raiz:

```sh
sudo chown -R "$(id -u):$(id -g)" /opt/projetos/exo/target
sudo chown -R "$(id -u):$(id -g)" /opt/projetos/exo/extensao/*/target
```

Isso trata o sintoma. Se a divergencia **voltar depois de um build**, a causa
esta' no `docker run` daquele script, nao aqui: procure `--user root`.

## Fora deste padrao, conhecido e NAO corrigido

Levantado na mesma auditoria, deixado como esta' por estar fora do escopo:

- `conf/truststore/truststore-pmo.p12` — `root:root 0600`; o `saexo` nem le'.
  Pode ser deliberado (material de chave). Decidir antes de mexer.
- `.git/objects/**` — 21 objetos `root:root`, resquicio de algum `git` sob
  sudo. Sao imutaveis e legiveis, entao nao atrapalham o dia a dia, mas um
  `git gc` rodado como `saexo` pode tropecar neles.
