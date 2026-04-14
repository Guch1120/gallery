#!/usr/bin/env bash
set -euo pipefail

cd /workspace

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

echo "[adb devices]"
adb devices || true
echo

echo "────────────────────────────────────────"
echo "Ready."
echo "────────────────────────────────────────"

exec bash