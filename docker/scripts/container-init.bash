#!/usr/bin/env bash
set -euo pipefail

cd /workspace

LOCAL_PROPERTIES_PATH="/workspace/Android/src/local.properties"

mkdir -p /workspace/Android/src
cat > "$LOCAL_PROPERTIES_PATH" <<'EOF'
sdk.dir=/opt/android-sdk
EOF

echo "────────────────────────────────────────"
echo "Android dev container initialized"
echo "PWD: $(pwd)"
echo "USER: $(whoami)"
echo "────────────────────────────────────────"

echo "[java -version]"
java -version || true
echo

echo "[javac -version]"
javac -version || true
echo

echo "[adb version]"
adb version || true
echo

echo "[sdkmanager --version]"
sdkmanager --version || true
echo

echo "[local.properties]"
cat "$LOCAL_PROPERTIES_PATH" || true

echo "[adb devices]"
adb devices || true
echo

echo "────────────────────────────────────────"
echo "Ready."
echo "────────────────────────────────────────"

exec bash
