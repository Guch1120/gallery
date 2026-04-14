#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DOCKER_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_DIR="$(cd "${DOCKER_DIR}/.." && pwd)"

DOCKER_COMPOSE_PATH="${DOCKER_DIR}/docker-compose.yml"
DOCKER_CONTAINER_NAME="android-dev"
ON_ENTER_CONTAINER_SCRIPT="/workspace/docker/scripts/start-android-dev.bash"

xhost +local:docker

cd "${PROJECT_DIR}"

if docker ps --format '{{.Names}}' | grep -qx "$DOCKER_CONTAINER_NAME"; then
    echo "$DOCKER_CONTAINER_NAME コンテナは既に起動中"
else
    echo "$DOCKER_CONTAINER_NAME コンテナを起動"
    docker compose -f "$DOCKER_COMPOSE_PATH" up -d "$DOCKER_CONTAINER_NAME"
fi

echo "コンテナ内でTerminatorを起動します..."
docker compose -f "$DOCKER_COMPOSE_PATH" exec -it "$DOCKER_CONTAINER_NAME" "$ON_ENTER_CONTAINER_SCRIPT"