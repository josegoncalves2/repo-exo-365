#!/bin/bash
# construir.sh — Gestao da Stack
set -euo pipefail
cd "$(dirname "$0")"
NUCLEO="src"
TARGET="target"
JAR="$TARGET/stack.jar"

rm -rf "$TARGET"
mkdir -p "$TARGET/classes"

docker run --rm --user "$(id -u):$(id -g)" -v "$(pwd):/src" -w /src \
  exo-pmo:7.2.1-addons2 \
  sh -c '
    mkdir -p /tmp/build/classes/conf/portal
    CP=$(find /opt/exo/lib -name "*.jar" | tr "\n" ":")
    echo "    $(find /opt/exo/lib -name "*.jar" | wc -l) jars no classpath"
    javac -encoding UTF-8 -proc:none \
      -cp "$CP" \
      -d /tmp/build/classes \
      /src/src/br/pmo/stack/StackManager.java /src/src/br/pmo/stack/StackRest.java
    cp /src/conf/configuration.xml /tmp/build/classes/conf/portal/
    cd /tmp/build/classes && jar --create --file /tmp/build/stack.jar .
    cp /tmp/build/stack.jar /src/target/stack.jar
    chmod 0644 /src/target/stack.jar
  '
