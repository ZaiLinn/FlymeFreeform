#!/bin/sh
# starter 路径的危险调用必须经过统一投递出口，单行豁免必须说明依据。
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
target=${STARTER_SAFETY_ROOT:-"$root/app/src/main/kotlin/io/github/zailinn/flymefreeform/hook/launch"}
python3 - "$target" <<'PY'
import os
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
try:
    if not root.is_dir():
        raise ValueError(f"starter safety source directory missing: {root}")
    def fail(error):
        raise error
    files = sorted(Path(parent) / name for parent, _, names in os.walk(root, onerror=fail)
                   for name in names if name.endswith('.kt'))
    if not files:
        raise ValueError(f"starter safety source directory has no Kotlin files: {root}")
    dangerous = re.compile(
        r'\b(?:sendBroadcast(?:AsUser)?|collapsePanels|exitFlexibleTask|Handler)\s*(?:\(|\{)'
        r'|\.\s*post(?:Delayed|AtFrontOfQueue)?\s*(?:\(|\{)'
        r'|\bZoomTaskCloser\s*\.\s*closer')
    literals = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')
    def blank(match):
        return ''.join('\n' if c == '\n' else ' ' for c in match.group())
    failed = False
    for path in files:
        if path == root / 'StarterSideEffects.kt':
            continue
        source = path.read_text(encoding='utf-8')
        # 行级白名单必须有非空理由；在去掉注释之前处理。
        allowed = re.compile(r'^.*// starter-safety: allow[ \t]+\S[^\n]*$', re.MULTILINE)
        code = literals.sub(blank, allowed.sub(blank, source))
        if 'decision' in path.relative_to(root).parts[:-1] and re.search(r'\bandroid\s*\.', literals.sub(blank, source)):
            print(f'{path}: decision package must not depend on android.*')
            failed = True
        for match in dangerous.finditer(code):
            line = source.count('\n', 0, match.start()) + 1
            print(f'{path}:{line}: {source.splitlines()[line - 1].strip()}')
            failed = True
    sys.exit(1 if failed else 0)
except (OSError, ValueError) as error:
    print(f'STARTER_SAFETY_SCAN_FAILED: {error}', file=sys.stderr)
    sys.exit(2)
PY
