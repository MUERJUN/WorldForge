#!/usr/bin/env bash
# v0.5.7 P1-3: real Gradle builds of the three generated projects.
# Layout: build/three-projects/<mod>/<mod>/  (out dir + project dir)
set -euo pipefail
cd "$(dirname "$0")/.."

BASE=build/three-projects
export JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to a JDK 21}"
PATH="$JAVA_HOME/bin:$PATH"

for proj in alpha_mod beta_mod gamma_mod; do
  dir="$BASE/$proj/$proj"
  if [ ! -d "$dir" ]; then
    echo "SKIP $dir (not generated)"; continue
  fi
  echo "=== building $dir ==="
  (cd "$dir" && ./gradlew build -x runGameTest --no-daemon --console=plain)
  jar="$dir/build/libs/$proj-1.0.0.jar"
  if [ ! -f "$jar" ]; then
    echo "FAIL: no JAR for $proj"; exit 1
  fi
  # fabric.mod.json entry must exist inside the jar; no worldforge references
  entry=$(unzip -p "$jar" fabric.mod.json | grep -o '"main": *\["[^"]*"' | head -1 | sed 's/.*\["//; s/"$//')
  cls=$(echo "$entry" | tr '.' '/')
  if ! unzip -l "$jar" | grep -q "$cls.class"; then
    echo "FAIL: $proj entry class $entry not in jar"; exit 1
  fi
  if unzip -l "$jar" | grep -qi "worldforge"; then
    echo "FAIL: $proj jar references worldforge"; exit 1
  fi
  echo "JAR OK: $proj -> $entry"
done
echo "ALL THREE PROJECTS BUILT"
