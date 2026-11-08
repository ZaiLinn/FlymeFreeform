#!/bin/sh
# starter 路径的危险调用必须经过统一投递出口，单行豁免必须说明依据。
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
target=${STARTER_SAFETY_ROOT:-"$root/app/src/main/kotlin/io/github/zailinn/flymefreeform/hook/launch"}
find "$target" -name '*.kt' ! -name StarterSideEffects.kt -exec awk '
    /\/\/ starter-safety: allow[[:space:]]+[^[:space:]]/ { next }
    /sendBroadcast[[:space:]]*\(|collapsePanels|exitFlexibleTask|Handler[[:space:]]*\(|\.post[[:space:]]*\(|\.postDelayed[[:space:]]*\(|\.postAtFrontOfQueue[[:space:]]*\(|ZoomTaskCloser\.closer/ {
        print FILENAME ":" FNR ":" $0
    }
' {} + | awk '{ print; failed=1 } END { exit failed }'
