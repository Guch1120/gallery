#!/usr/bin/env bash
set -euo pipefail

service dbus start
echo "DBus service started"

TERMINATOR_LAYOUT_FILE="/workspace/terminator/terminator_layout"

echo "[debug] ls -l /workspace"
ls -l /workspace || true
echo

# echo "[debug] ls -l /workspace/terminator"
# ls -l /workspace/terminator || true
# echo

if [ ! -f "$TERMINATOR_LAYOUT_FILE" ]; then
    echo "Error: Terminator レイアウト設定ファイル $TERMINATOR_LAYOUT_FILE が存在しません。" 1>&2
    echo "\"layout=androiddev\" を記述した $TERMINATOR_LAYOUT_FILE を作成してください。" 1>&2
    exit 1
fi

source "$TERMINATOR_LAYOUT_FILE"

if [ -z "${layout:-}" ]; then
    echo "Error: $TERMINATOR_LAYOUT_FILE 内に layout が定義されていません。" 1>&2
    exit 1
fi

if [ "$layout" != "androiddev" ]; then
    echo "Error: layout=\"$layout\" は許可されていません。" 1>&2
    echo "許可されているレイアウト: androiddev" 1>&2
    exit 1
fi

echo "Using Terminator layout: $layout"

terminator -m -l "$layout"