#!/bin/bash
# construir.sh — Gestao de Backup e Migracao
set -euo pipefail
cd "$(dirname "$0")"
NUCLEO="src"
TARGET="target"
JAR="$TARGET/gestao.jar"

rm -rf "$TARGET"
mkdir -p "$TARGET/classes"

docker run --rm --user "$(id -u):$(id -g)" -v "$(pwd):/src" -w /src \
  exo-pmo:7.2.1-addons2 \
  sh -c '
    mkdir -p /tmp/build/classes/conf/portal
    CP=$(find /opt/exo/lib -name "*.jar" | tr "\n" ":")
    echo "    $(find /opt/exo/lib -name "*.jar" | wc -l) jars no classpath"
    javac -encoding UTF-8 \
      -cp "$CP" \
      -d /tmp/build/classes \
      /src/src/br/pmo/gestao/GestaoPlataforma.java /src/src/br/pmo/gestao/GestaoRest.java && \
    cp /src/conf/configuration.xml /tmp/build/classes/conf/portal/ && \
    cd /tmp/build/classes && jar --create --file /tmp/build/gestao.jar . && \
    cp /tmp/build/gestao.jar /src/target/gestao.jar && \
    chmod 0644 /src/target/gestao.jar
  '
