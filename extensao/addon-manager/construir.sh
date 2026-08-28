#!/bin/bash
# construir.sh — Add-on Manager
set -euo pipefail
cd "$(dirname "$0")"
NUCLEO="src"
TARGET="target"
JAR="$TARGET/addon-manager.jar"

rm -rf "$TARGET"
mkdir -p "$TARGET/classes"

# Compila dentro do container (javax.ws.rs esta no classpath do exo)
docker run --rm --user "$(id -u):$(id -g)" -v "$(pwd):/src" -w /src \
  exo-pmo:7.2.1-addons2 \
  sh -c '
    mkdir -p /tmp/build/classes/conf/portal
    CP=$(find /opt/exo/lib -name "*.jar" | tr "\n" ":")
    echo "    $(find /opt/exo/lib -name "*.jar" | wc -l) jars no classpath"
    javac -encoding UTF-8 -proc:none \
      -cp "$CP" \
      -d /tmp/build/classes \
      $(find /src/src -name "*.java")
    cp /src/conf/configuration.xml /tmp/build/classes/conf/portal/
    cd /tmp/build/classes && jar --create --file /tmp/build/addon-manager.jar .
    cp /tmp/build/addon-manager.jar /src/target/addon-manager.jar
    chmod 0644 /src/target/addon-manager.jar
  '
