#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_dir="$(cd "${script_dir}/.." && pwd)"

cd "${app_dir}"

if [[ -d android || -d ios ]]; then
  echo "Platform directories already exist. Skipping flutter create."
  exit 0
fi

flutter create \
  --platforms=android,ios \
  --project-name flutter_llama_gallery_app \
  --org com.google.ai.edge \
  .

