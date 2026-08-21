#!/usr/bin/env bash
# ===================================================================
# run_all.sh — executa a suíte completa e registra tudo na auditoria.
#
# Toda execução:
#   * recebe um RUN_ID único (evita colisão com execuções anteriores);
#   * grava saída bruta em evidence/;
#   * acrescenta uma entrada em AUDIT.md via scripts/audit.sh;
#   * devolve código de saída != 0 se qualquer teste falhar.
# ===================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PY="${ROOT}/tests/.venv/bin/python"
export RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
# Credenciais e URL vêm do .env (não versionado), e não de valores fixos.
# Sem isto a suíte usaria o padrão histórico 'gtn' de tests/exolib.py e
# TODOS os testes autenticados falhariam — sem indicar o motivo real.
if [[ -f "${ROOT}/.env" ]]; then
  set -a; source "${ROOT}/.env"; set +a
fi
export EXO_BASE="${EXO_BASE:-http://${EXO_PROXY_VHOST:-192.168.1.59}}"
export EXO_ADMIN_USER="${EXO_ADMIN_USER:-root}"
export EXO_ADMIN_PASS="${EXO_ADMIN_PASS:?defina EXO_ADMIN_PASS no .env}"
export MAILPIT_BASE="${MAILPIT_BASE:-http://192.168.1.59:8025}"

if [[ ! -x "$PY" ]]; then
  echo "ERRO: ambiente virtual ausente em tests/.venv — rode a preparação primeiro." >&2
  exit 2
fi

SUITES=("$@")
if [[ ${#SUITES[@]} -eq 0 ]]; then
  mapfile -t SUITES < <(find "${ROOT}/tests" -maxdepth 1 -name 'test_*.py' | sort)
fi

echo "==================================================================="
echo " SUITE DE TESTES — eXo Platform Community"
echo " RUN_ID : ${RUN_ID}"
echo " BASE   : ${EXO_BASE}"
echo " Suites : ${#SUITES[@]}"
echo "==================================================================="

total_rc=0
declare -a resumo=()

for suite in "${SUITES[@]}"; do
  nome="$(basename "$suite" .py)"
  echo
  echo "-------------------------------------------------------------------"
  echo ">> $nome"
  echo "-------------------------------------------------------------------"
  log="${ROOT}/evidence/execucao-${nome}-${RUN_ID}.log"
  "$PY" "$suite" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  [[ $rc -ne 0 ]] && total_rc=1
  passou=$(grep -c '^\[PASSOU\]' "$log" 2>/dev/null || echo 0)
  falhou=$(grep -c '^\[FALHOU\]' "$log" 2>/dev/null || echo 0)
  resumo+=("${nome}: ${passou} passaram, ${falhou} falharam (rc=${rc})")

  "${ROOT}/scripts/audit.sh" entry \
    "Execucao da suite ${nome} (RUN_ID ${RUN_ID})" \
    "Execucao automatizada de testes sob dupla abordagem (A: maquina/API, B: usuario final em navegador real)." \
    "tests/run_all.sh ${nome}" \
    "${passou} testes passaram, ${falhou} falharam. Codigo de saida ${rc}." \
    "evidence/execucao-${nome}-${RUN_ID}.log e evidence/resultado-*-${RUN_ID}.json" \
    "$([[ $rc -eq 0 ]] && echo OK || echo FALHA)" >/dev/null
done

echo
echo "==================================================================="
echo " RESUMO GERAL — RUN_ID ${RUN_ID}"
echo "==================================================================="
printf ' %s\n' "${resumo[@]}"
echo
if [[ $total_rc -eq 0 ]]; then
  echo " RESULTADO: TODAS AS SUITES PASSARAM"
else
  echo " RESULTADO: HOUVE FALHAS — ver evidence/ para a saida bruta"
fi
exit $total_rc
