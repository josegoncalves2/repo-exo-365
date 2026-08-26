#!/usr/bin/env bash
# ============================================================================
# instalar.sh -- do 'git clone' a' stack funcional, em um comando.
#
#   git clone <repo> /opt/projetos/exo && cd /opt/projetos/exo
#   ./scripts/instalar.sh 192.168.1.59
#
# IDEMPOTENTE: rodar de novo num servidor ja' instalado nao refaz nada -- cada
# passo verifica antes de agir e diz o que encontrou. Nao apaga dado nenhum:
# se ./data existe, a stack e' apenas iniciada; a reconstrucao do zero (que
# APAGA) so' acontece quando nao ha dado algum, e nunca em cima de um banco.
#
# O QUE ESTE SCRIPT REPRODUZ
#   . segredos (.env), certificados TLS, exo.properties
#   . os 14 containers da stack, na ordem, com healthcheck
#   . as duas unidades systemd (boot da stack + interface da estrutura)
#   . a hierarquia organizacional descrita em conf/estrutura/*.json
#
# O QUE ELE NAO PODE REPRODUZIR (e nenhum script poderia)
#   . o CONTEUDO ja' produzido no servidor antigo: publicacoes, arquivos,
#     mensagens, contas criadas na mao. Isso e' dado, mora em ./data e no
#     MySQL, e viaja por BACKUP -- scripts/backup.sh e a restauracao dele.
# ============================================================================
set -uo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"
USUARIO="$(id -un)"
VHOST="${1:-}"
GATEWAY=""                    # descoberto no passo 5, da rede real (ver por que abaixo)
FALHAS=0

azul(){ printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
ok(){   printf '   \033[32mok\033[0m      %s\n' "$*"; }
jaha(){ printf '   \033[2mja existe\033[0m %s\n' "$*"; }
erro(){ printf '   \033[31mFALHA\033[0m   %s\n' "$*"; FALHAS=$((FALHAS+1)); }

sudo_ok(){ sudo -n true 2>/dev/null || { [ -n "${SUDO_ASKPASS:-}" ] && sudo -A true 2>/dev/null; }; }
SUDO=""; sudo -n true 2>/dev/null && SUDO="sudo -n"
[ -z "$SUDO" ] && [ -n "${SUDO_ASKPASS:-}" ] && sudo -A true 2>/dev/null && SUDO="sudo -A"

# ---------------------------------------------------------------- 1. requisitos
azul "1/7  Requisitos do servidor"
for cmd in docker python3 openssl curl; do
  command -v "$cmd" >/dev/null && ok "$cmd" || erro "$cmd nao encontrado -- instale antes"
done
docker compose version >/dev/null 2>&1 && ok "docker compose (plugin v2)" \
  || erro "plugin 'docker compose' v2 ausente"
[ -n "$SUDO" ] && ok "sudo sem interacao" \
  || erro "sudo sem interacao (NOPASSWD ou SUDO_ASKPASS) -- necessario para as unidades systemd"
mem=$(free -m | awk '/Mem:/{print $2}')
[ "$mem" -ge 7000 ] && ok "memoria: ${mem} MB" || erro "memoria ${mem} MB -- o eXo pede ~8 GB"
[ "$FALHAS" -gt 0 ] && { printf '\nInterrompido: resolva os itens acima.\n'; exit 1; }

# ---------------------------------------------------------------- 2. segredos
azul "2/7  Segredos e configuracao"
if [ -f .env ]; then
  jaha ".env (mantido -- sobrescrever invalidaria as senhas ja gravadas no banco)"
else
  [ -z "$VHOST" ] && { erro "primeira instalacao: informe o IP/hostname. Ex.: ./scripts/instalar.sh 192.168.1.59"; exit 1; }
  ./scripts/gerar-segredos.sh "$VHOST" >/dev/null && ok ".env gerado para $VHOST" || erro "gerar-segredos.sh"
fi
if [ -f conf/exo.properties ]; then jaha "conf/exo.properties"
else cp conf/exo.properties.example conf/exo.properties && ok "conf/exo.properties criado do exemplo"; fi

# ---------------------------------------------------------------- 3. certificados
azul "3/7  Certificados TLS da CA interna"
# ATENCAO AOS NOMES. A primeira versao deste teste procurava 'ca.pem' e
# 'jitsi-cert.pem', que NAO EXISTEM -- os arquivos sao ca-chain/ca-root/ca-int e
# jitsi-fullchain. Resultado: o teste falhava sempre e o script REGERAVA os
# certificados de uma stack em producao, trocando no disco a chave que o MySQL
# ja' tinha carregado. Foi preciso restaurar do git. Os nomes abaixo sao os
# reais, conferidos em disco -- nao mude sem olhar o diretorio.
if [ -s conf/mysql-certs/ca-chain.pem ] && [ -s conf/mysql-certs/server-key.pem ] \
   && [ -s conf/portal-certs/portal-cert.pem ] \
   && [ -s conf/jitsi-certs/jitsi-fullchain.pem ]; then
  jaha "mysql-certs, portal-certs e jitsi-certs (nao regero: trocar cert de servico no ar quebra TLS)"
else
  ./scripts/gerar-certificados-mysql.sh >/dev/null 2>&1 \
    && ok "certificados gerados (MySQL, portal e Jitsi)" || erro "gerar-certificados-mysql.sh"
fi

# ---------------------------------------------------------------- 4. stack
azul "4/7  Imagem do eXo e containers"
# A imagem do eXo e' CUSTOMIZADA (Dockerfile.exo: idioma pt-BR, branding, CSS,
# JS e o addon do Jitsi). Num servidor novo ela nao existe, e o compose a
# construiria sozinho no meio da subida ordenada -- varios minutos de silencio
# que parecem travamento. Aqui a construcao e' um passo explicito e anunciado.
set -a; source .env; set +a
if docker image inspect "${EXO_IMAGE}" >/dev/null 2>&1; then
  jaha "imagem ${EXO_IMAGE}"
else
  # Os add-ons oficiais (conf/addons/manifesto.json) sao BAIXADOS AQUI, no host,
  # e nao dentro do build: o `docker build` instala a partir de
  # conf/addons/cache/ por catalogo file://, sem tocar a rede. Sao ~30 MB que
  # NAO ficam no git -- o que garante reprodutibilidade e' o sha256 selado no
  # manifesto, conferido tanto ao baixar quanto ao instalar. Ja' baixado, este
  # passo so' confere as somas e nao usa rede.
  ok "add-ons oficiais: conferindo o cache (baixa o que faltar)"
  ./scripts/addons.py baixar && ok "add-ons em conf/addons/cache" \
    || erro "addons.py baixar -- sem os zips o build nao instala add-on nenhum"
  ok "construindo ${EXO_IMAGE} (Dockerfile.exo) -- pode levar varios minutos"
  docker compose build exo && ok "imagem construida" || erro "docker compose build exo"
fi
if [ -d data ] && [ -n "$(ls -A data 2>/dev/null)" ]; then
  jaha "./data com conteudo -- NAO reconstruo (isso apagaria o banco); apenas subo"
  ./scripts/subir-ordenado.sh || erro "subir-ordenado.sh"
else
  ok "sem dados: instalacao limpa (leva de 10 a 20 min no primeiro boot do eXo)"
  ./scripts/reconstruir-do-zero.sh || erro "reconstruir-do-zero.sh"
fi
esperados=$(docker compose config --services | wc -l)
subindo=$(docker compose ps --services --filter status=running 2>/dev/null | wc -l)
[ "$subindo" -ge "$esperados" ] && ok "$subindo/$esperados servicos de pe" \
  || erro "$subindo/$esperados servicos de pe -- veja 'docker compose ps'"

# ---------------------------------------------------------------- 5. systemd
azul "5/7  Endereco da interface, nginx e unidades systemd"
# O gateway da rede exo_net e' o endereco pelo qual o nginx (container) alcanca
# a interface da estrutura (processo do host). O Docker escolhe a faixa quando
# cria a rede -- 172.20 aqui, 172.21 em outro servidor. Fixar a faixa no compose
# quebraria stacks ja de pe (o up tentaria recriar a rede em uso), entao o
# endereco e' LIDO da rede real e escrito nos dois lugares que dependem dele.
GATEWAY="$(docker network inspect exo_net \
  --format '{{range .IPAM.Config}}{{.Gateway}}{{end}}' 2>/dev/null)"
if [ -z "$GATEWAY" ]; then
  erro "nao consegui ler o gateway da rede exo_net -- a stack subiu?"
else
  ok "gateway da rede exo_net: $GATEWAY"
  atual=$(grep -oE 'proxy_pass http://[0-9.]+:878/' conf/nginx.conf | head -1)
  if [ "$atual" = "proxy_pass http://$GATEWAY:878/" ]; then
    jaha "conf/nginx.conf ja aponta para $GATEWAY:878"
  else
    # escrita PRESERVANDO O INODE: conf/nginx.conf entra no container por bind
    # mount, e 'sed -i' cria um arquivo novo -- o mount continuaria preso ao
    # arquivo antigo e o nginx seguiria lendo a versao velha ate ser recriado.
    python3 - "$GATEWAY" <<'PYNGINX'
import re, sys
gw = sys.argv[1]
p = "conf/nginx.conf"
t = open(p, encoding="utf-8").read()
novo = re.sub(r"proxy_pass http://[0-9.]+:878/", f"proxy_pass http://{gw}:878/", t)
if novo != t:
    open(p, "w", encoding="utf-8").write(novo)   # trunca o MESMO inode
PYNGINX
    docker exec exo-web nginx -s reload >/dev/null 2>&1
    ok "conf/nginx.conf apontado para $GATEWAY:878 e nginx recarregado"
  fi
fi
instalar_unidade(){         # $1 = nome do arquivo em deploy/
  local nome="$1" tmp
  tmp="$(mktemp)"
  sed -e "s|@RAIZ@|$RAIZ|g" -e "s|@USUARIO@|$USUARIO|g" -e "s|@GATEWAY@|$GATEWAY|g" \
      "deploy/$nome" > "$tmp"
  if [ -f "/etc/systemd/system/$nome" ] && cmp -s "$tmp" "/etc/systemd/system/$nome"; then
    jaha "$nome (identica)"; rm -f "$tmp"; return 0
  fi
  $SUDO cp "$tmp" "/etc/systemd/system/$nome" && rm -f "$tmp" \
    && $SUDO systemctl daemon-reload && ok "$nome instalada/atualizada" || erro "$nome"
}
instalar_unidade exo.service
instalar_unidade exo-estrutura.service
$SUDO systemctl enable exo.service >/dev/null 2>&1 && ok "exo.service habilitada no boot" || erro "enable exo.service"
$SUDO systemctl enable --now exo-estrutura.service >/dev/null 2>&1 \
  && ok "exo-estrutura.service habilitada e ativa" || erro "enable exo-estrutura.service"

# ---------------------------------------------------------------- 6. hierarquia
azul "6/7  Hierarquia organizacional"
if compgen -G "conf/estrutura/*.json" >/dev/null; then
  # espera o portal responder antes de provisionar: no primeiro boot o Tomcat
  # demora, e provisionar cedo falharia com 'login falhou'.
  printf '   aguardando o portal responder'
  for _ in $(seq 1 120); do
    curl -sf -o /dev/null "http://127.0.0.1/portal/login" && break
    printf '.'; sleep 10
  done; printf '\n'
  for arq in conf/estrutura/*.json; do
    EXO_URL=http://127.0.0.1 EXO_ADMIN_USER="${EXO_ADMIN_USER:-root}" \
    EXO_ADMIN_PASS="${EXO_ADMIN_PASS:-$(grep -E '^EXO_ADMIN_PASS=' .env | cut -d= -f2-)}" \
      ./scripts/estrutura-organizacional.py --arquivo "$arq" >/dev/null 2>&1 \
      && ok "$(basename "$arq") provisionado (idempotente: reaplica sem duplicar)" \
      || erro "$(basename "$arq") -- rode a mao para ver o log: ./scripts/estrutura-organizacional.py --arquivo $arq"
  done
else
  jaha "nenhum conf/estrutura/*.json -- crie a hierarquia pela tela /estrutura/"
fi

# ---------------------------------------------------------------- 7. veredito
azul "7/7  Conferencia final"
verifica(){ local rot="$1" cod; cod=$(curl -sk -o /dev/null -w '%{http_code}' "$2");
  case "$cod" in 200|302) ok "$rot ($cod)";; *) erro "$rot devolveu $cod";; esac; }
verifica "portal            http://127.0.0.1/portal/" "http://127.0.0.1/portal/"
verifica "estrutura         /estrutura/"               "http://127.0.0.1/estrutura/"
# Sem cookie do portal, 401 e' a resposta CERTA: o endpoint existe e esta
# protegido. Exigir 200 aqui era cobrar que ele servisse arquivo a anonimo.
cod=$(curl -sk -o /dev/null -w '%{http_code}' "http://127.0.0.1/estrutura/api/modelo.csv")
case "$cod" in
  401|302) ok "modelo de CSV     protegido por sessao ($cod)";;
  200)     ok "modelo de CSV     servido ($cod)";;
  *)       erro "modelo de CSV devolveu $cod (esperado 401 sem sessao)";;
esac
doentes=$(docker ps --filter health=unhealthy --format '{{.Names}}' | tr '\n' ' ')
[ -z "$doentes" ] && ok "nenhum container doente" || erro "containers doentes: $doentes"

printf '\n'
if [ "$FALHAS" -eq 0 ]; then
  printf '\033[1;32mINSTALACAO COMPLETA.\033[0m  Portal: http://%s/portal/   Estrutura: http://%s/estrutura/\n' \
    "${VHOST:-127.0.0.1}" "${VHOST:-127.0.0.1}"
  printf 'Conteudo (publicacoes, arquivos, contas) NAO vem do git -- restaure um backup se precisar.\n'
else
  printf '\033[1;31m%s ITEM(NS) COM FALHA.\033[0m Releia os pontos marcados acima.\n' "$FALHAS"
  exit 1
fi
