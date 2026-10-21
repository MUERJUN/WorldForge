#!/usr/bin/env bash
# v0.6.0-gate P0-1/P1-3: real Gradle builds of the three generated projects.
# Layout: build/three-projects/<mod>/<mod>/  (out dir + project dir)
#
# Hard rules:
#  * set -euo pipefail — every stage failure aborts with a non-zero exit
#  * a missing project is a FAILURE, never a SKIP
#  * builds run ONLY through each project's own Gradle wrapper
#  * GRADLE_USER_HOME is isolated to a fresh empty dir unless the caller
#    already exported one — proves no reliance on a pre-populated cache or a
#    system-installed Gradle
set -euo pipefail
cd "$(dirname "$0")/.."

BASE=build/three-projects
export JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to a JDK 21}"
PATH="$JAVA_HOME/bin:$PATH"

# isolate the Gradle user home (blank cache) unless CI exported one already
if [ -z "${GRADLE_USER_HOME:-}" ]; then
  GRADLE_USER_HOME="$(pwd)/build/ci-gradle-home"
  export GRADLE_USER_HOME
  rm -rf "$GRADLE_USER_HOME"
  mkdir -p "$GRADLE_USER_HOME"
fi
echo "GRADLE_USER_HOME=$GRADLE_USER_HOME"

# prove we are NOT using a system gradle: the wrapper must exist per project
for proj in alpha_mod beta_mod gamma_mod; do
  dir="$BASE/$proj/$proj"
  if [ ! -d "$dir" ]; then
    echo "FAIL: $dir does not exist — generation step must have run first"
    exit 1
  fi
  if [ ! -f "$dir/gradlew" ] && [ ! -f "$dir/gradlew.bat" ]; then
    echo "FAIL: $dir has no Gradle wrapper"
    exit 1
  fi
done

for proj in alpha_mod beta_mod gamma_mod; do
  dir="$BASE/$proj/$proj"
  echo "=== building $dir (wrapper-only, isolated home) ==="
  (cd "$dir" && ./gradlew build -x runGameTest --no-daemon --console=plain)
  jar="$dir/build/libs/$proj-1.0.0.jar"
  if [ ! -f "$jar" ]; then
    echo "FAIL: no JAR for $proj"; exit 1
  fi
  # fabric.mod.json entry must exist inside the jar; no worldforge references
  entry=$(unzip -p "$jar" fabric.mod.json | grep -o '"main": *\["[^"]*"' | head -1 | sed 's/.*\["//; s/"$//')
  if [ -z "$entry" ]; then
    echo "FAIL: $proj fabric.mod.json has no main entry"; exit 1
  fi
  cls=$(echo "$entry" | tr '.' '/')
  if ! unzip -l "$jar" | grep -q "$cls.class"; then
    echo "FAIL: $proj entry class $entry not in jar"; exit 1
  fi
  if unzip -l "$jar" | grep -qi "worldforge"; then
    echo "FAIL: $proj jar references worldforge"; exit 1
  fi
  echo "JAR OK: $proj -> $entry"
done

# real Fabric load: run each project's GameTests on this OS
for proj in alpha_mod beta_mod gamma_mod; do
  dir="$BASE/$proj/$proj"
  echo "=== runGameTest $proj ==="
  (cd "$dir" && ./gradlew runGameTest --no-daemon --console=plain > /tmp/gt-$proj.log 2>&1) \
    || { echo "FAIL: $proj runGameTest exited non-zero"; tail -20 /tmp/gt-$proj.log; exit 1; }
  if ! grep -q "required tests passed" /tmp/gt-$proj.log; then
    echo "FAIL: $proj did not report 'required tests passed'"; tail -20 /tmp/gt-$proj.log; exit 1
  fi
  grep "required tests passed" /tmp/gt-$proj.log | tail -1 | sed "s/^/  $proj: /"
done

echo "ALL THREE PROJECTS BUILT AND GAME-TESTED"
