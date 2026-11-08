#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
fixture=$(mktemp -d "${TMPDIR:-/tmp}/starter-safety-test.XXXXXX")
trap 'rm -rf "$fixture"' EXIT HUP INT TERM
check="$root/scripts/check-starter-safety.sh"
expect_failure() {
    if STARTER_SAFETY_ROOT="$1" sh "$check" >"$fixture/result" 2>&1; then
        cat "$fixture/result"
        echo "expected safety failure: $1" >&2
        exit 1
    fi
}
expect_failure "$fixture/missing"
mkdir "$fixture/empty" "$fixture/source with spaces"
expect_failure "$fixture/empty"
source_dir="$fixture/source with spaces"
cat > "$source_dir/Unsafe.kt" <<'EOF'
fun unsafe() { context.sendBroadcast(intent) }
EOF
expect_failure "$source_dir"
cat > "$source_dir/Unsafe.kt" <<'EOF'
fun unsafe() { handler.post { work() } }
EOF
expect_failure "$source_dir"
cat > "$source_dir/Unsafe.kt" <<'EOF'
fun unsafe() {
    handler
       .postDelayed
       (task, 10)
}
EOF
expect_failure "$source_dir"
cat > "$source_dir/Unsafe.kt" <<'EOF'
fun unsafe() { context.sendBroadcastAsUser(intent, user) }
EOF
expect_failure "$source_dir"
cat > "$source_dir/Unsafe.kt" <<'EOF'
// handler.post { ignored() }
val sample = "context.sendBroadcast(intent)"
/* exitFlexibleTask(task) */
fun safe() { effects.post("safe", action) } // starter-safety: allow centralized dispatcher
EOF
STARTER_SAFETY_ROOT="$source_dir" sh "$check"
cat > "$source_dir/StarterSideEffects.kt" <<'EOF'
fun centralized() { handler.post { context.sendBroadcast(intent) } }
EOF
STARTER_SAFETY_ROOT="$source_dir" sh "$check"
mkdir "$source_dir/decision"
cat > "$source_dir/decision/PlatformLeak.kt" <<'EOF'
import android.os.Bundle
EOF
expect_failure "$source_dir"
echo 'Starter safety scanner: 9 regression scenarios passed'
