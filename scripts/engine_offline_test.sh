#!/usr/bin/env bash
# Compile :engine and run its JUnit 4 tests WITHOUT Gradle or network, using the Kotlin compiler and
# runtime jars that ship inside a Gradle distribution (Gradle bundles kotlin-compiler-embeddable,
# kotlinx-coroutines-core and kotlinx-serialization-json for its own Kotlin DSL).
#
# Why this exists: the sandbox that scaffolded this repo had no access to Maven Central, so this was
# the only way to prove the engine compiles and its tests pass. On a normal machine just run
# `./gradlew :engine:test`. Keep this script anyway — it's a 5-second check with zero setup.
#
# Usage: scripts/engine_offline_test.sh [path-to-gradle-dist]   (default: $GRADLE_HOME or /opt/gradle)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_DIST="${1:-${GRADLE_HOME:-/opt/gradle}}"
GL="$GRADLE_DIST/lib"
OUT="$ROOT/.offline-build"
rm -rf "$OUT" && mkdir -p "$OUT/main" "$OUT/test"

pick() { ls "$GL"/$1 2>/dev/null | head -1; }
need() { local f; f="$(pick "$1")"; [ -n "$f" ] || { echo "missing $1 in $GL" >&2; exit 1; }; echo "$f"; }

STDLIB="$(need 'kotlin-stdlib-*.jar')"
REFLECT="$(need 'kotlin-reflect-*.jar')"
COMPILER="$(need 'kotlin-compiler-embeddable-*.jar')"
SCRIPT_RT="$(need 'kotlin-script-runtime-*.jar')"
DAEMON="$(pick 'kotlin-daemon-embeddable-*.jar')"
COROUTINES="$(need 'kotlinx-coroutines-core-jvm-*.jar')"
SER_CORE="$(need 'kotlinx-serialization-core-jvm-*.jar')"
SER_JSON="$(need 'kotlinx-serialization-json-jvm-*.jar')"
JUNIT="$(need 'junit-4*.jar')"
HAMCREST="$(pick 'hamcrest-core-*.jar')"
TROVE="$(pick 'trove4j-*.jar')"
ANNOT="$(pick 'annotations-*.jar')"

RUNTIME_CP="$STDLIB:$COROUTINES:$SER_CORE:$SER_JSON"
COMPILER_CP="$COMPILER:$STDLIB:$REFLECT:$SCRIPT_RT:$DAEMON:$COROUTINES:$TROVE:$ANNOT"

kotlinc() {
  java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "$@" 2>&1 | grep -v 'JAVA_TOOL_OPTIONS' || true
}

echo "== compiling :engine main"
MAIN_SRC=$(find "$ROOT/engine/src/main/kotlin" -name '*.kt')
kotlinc -classpath "$RUNTIME_CP" -d "$OUT/main" $MAIN_SRC
[ -n "$(find "$OUT/main" -name '*.class' | head -1)" ] || { echo "main compile produced no classes" >&2; exit 1; }

echo "== compiling :engine tests"
TEST_SRC=$(find "$ROOT/engine/src/test/kotlin" -name '*.kt' 2>/dev/null || true)
if [ -z "$TEST_SRC" ]; then echo "no tests"; exit 0; fi
kotlinc -classpath "$RUNTIME_CP:$JUNIT:$OUT/main" -d "$OUT/test" $TEST_SRC
[ -n "$(find "$OUT/test" -name '*.class' | head -1)" ] || { echo "test compile produced no classes" >&2; exit 1; }

echo "== running tests"
# JUnit 4 needs hamcrest-core; Gradle's distribution may not carry it, so fall back to a tiny runner.
TEST_CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort)
if [ -n "$HAMCREST" ]; then
  java -cp "$OUT/test:$OUT/main:$RUNTIME_CP:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore $TEST_CLASSES 2>&1 | grep -v 'JAVA_TOOL_OPTIONS'
else
  # Minimal runner: instantiate each test class and invoke @Test methods reflectively.
  cat > "$OUT/Runner.java" <<'EOF'
import java.lang.reflect.*;
public class Runner {
  public static void main(String[] a) throws Exception {
    int run = 0, failed = 0;
    for (String cn : a) {
      Class<?> c = Class.forName(cn);
      for (Method m : c.getDeclaredMethods()) {
        if (m.getAnnotation(org.junit.Test.class) == null) continue;
        run++;
        Object o = c.getDeclaredConstructor().newInstance();
        try { m.invoke(o); }
        catch (InvocationTargetException e) {
          Throwable t = e.getCause();
          org.junit.Test ann = m.getAnnotation(org.junit.Test.class);
          if (ann.expected() != org.junit.Test.None.class && ann.expected().isInstance(t)) continue;
          failed++;
          System.out.println("FAIL " + cn + "." + m.getName() + ": " + t);
          StackTraceElement[] st = t.getStackTrace();
          for (int i = 0; i < Math.min(6, st.length); i++) System.out.println("    at " + st[i]);
        }
      }
    }
    System.out.println("tests run: " + run + ", failed: " + failed);
    if (failed > 0) System.exit(1);
  }
}
EOF
  javac -cp "$JUNIT" -d "$OUT" "$OUT/Runner.java" 2>&1 | grep -v 'JAVA_TOOL_OPTIONS' || true
  java -cp "$OUT:$OUT/test:$OUT/main:$RUNTIME_CP:$JUNIT" Runner $TEST_CLASSES 2>&1 | grep -v 'JAVA_TOOL_OPTIONS'
fi
