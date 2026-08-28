#!/bin/bash
# construir.sh — Conectores de Gamificacao
set -euo pipefail
cd "$(dirname "$0")"
NUCLEO="src"
TARGET="target"
JAR="$TARGET/gamificacao.jar"

rm -rf "$TARGET"
mkdir -p "$TARGET"

javac -Xlint:all -Werror -d "$TARGET/classes" \
  $(find "$NUCLEO" -name "*.java")

cd "$TARGET/classes"
jar cf "../gamificacao.jar" .
cd ../..
echo "==> pronto: $(pwd)/$JAR"
ls -la "$JAR"
