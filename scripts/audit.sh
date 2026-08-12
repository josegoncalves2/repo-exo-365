#!/usr/bin/env bash
# ===================================================================
# audit.sh — registrador da trilha de auditoria do projeto eXo
#
# Garante que NENHUMA atividade fique sem registro em AUDIT.md.
# Uso:
#   ./scripts/audit.sh entry "TITULO" "ACAO" "COMANDO" "RESULTADO" "EVIDENCIA" "STATUS"
#   ./scripts/audit.sh note  "texto livre"
#   ./scripts/audit.sh run   "TITULO" -- <comando...>
#
# O modo `run` executa o comando, captura stdout/stderr e o código de
# saída, grava a saída em evidence/ e registra a entrada automaticamente
# com STATUS derivado do exit code. É o modo preferido: torna
# impossível executar algo e esquecer de auditar.
# ===================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AUDIT="${ROOT}/AUDIT.md"
EVID="${ROOT}/evidence"
mkdir -p "$EVID"

ts()  { date '+%Y-%m-%d %H:%M:%S %Z'; }
seq_next() {
  # Próximo número sequencial, derivado das entradas já existentes
  local last
  last=$(grep -oE '^### \[[0-9]{3}\]' "$AUDIT" 2>/dev/null | grep -oE '[0-9]{3}' | sort -n | tail -1)
  printf '%03d' $(( 10#${last:-0} + 1 ))
}

cmd_entry() {
  local title="$1" acao="$2" comando="$3" resultado="$4" evidencia="$5" status="$6"
  local n; n="$(seq_next)"
  cat >> "$AUDIT" <<EOF

### [${n}] $(ts) — ${title}
**Ação:** ${acao}
**Comando/Arquivo:** \`${comando}\`
**Resultado:** ${resultado}
**Evidência:** ${evidencia}
**Status:** ${status}
EOF
  echo "[audit] registrado [${n}] ${title} (${status})"
}

cmd_note() {
  local n; n="$(seq_next)"
  cat >> "$AUDIT" <<EOF

### [${n}] $(ts) — Nota
$1
EOF
  echo "[audit] nota [${n}] registrada"
}

cmd_run() {
  local title="$1"; shift
  [[ "${1:-}" == "--" ]] && shift
  local n; n="$(seq_next)"
  local slug; slug=$(echo "$title" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9-' | cut -c1-50)
  local out="${EVID}/${n}-${slug}.log"

  {
    echo "# Evidência [${n}] — ${title}"
    echo "# Data:    $(ts)"
    echo "# Comando: $*"
    echo "# ---------------------------------------------------------"
  } > "$out"

  "$@" >> "$out" 2>&1
  local rc=$?

  echo "# --------------------------------------------------------- " >> "$out"
  echo "# exit code: ${rc}" >> "$out"

  local status resultado
  if [[ $rc -eq 0 ]]; then status="OK"; else status="FALHA"; fi
  resultado="Execução encerrada com código ${rc}. Saída completa preservada na evidência."

  cat >> "$AUDIT" <<EOF

### [${n}] $(ts) — ${title}
**Ação:** Execução auditada de comando.
**Comando/Arquivo:** \`$*\`
**Resultado:** ${resultado}
**Evidência:** \`evidence/${n}-${slug}.log\`
**Status:** ${status}
EOF
  echo "[audit] registrado [${n}] ${title} (${status}) -> evidence/${n}-${slug}.log"
  return $rc
}

case "${1:-}" in
  entry) shift; cmd_entry "$@" ;;
  note)  shift; cmd_note  "$@" ;;
  run)   shift; cmd_run   "$@" ;;
  *) echo "uso: $0 {entry|note|run} ..." >&2; exit 2 ;;
esac
