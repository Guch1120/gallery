#!/usr/bin/env python3

import base64
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_PROMPT_FILE = "prompt_to_camera.txt"
DEFAULT_ANSWER_FILE = "answer_from_camera.txt"
DEFAULT_IMAGE_DIR = "image_from_phone"
DEFAULT_SYSTEM_INSTRUCTION = "短く、事実だけを日本語で返してください。"


def main() -> int:
    prompt_path = Path(os.environ.get("CAMERA_PROMPT_FILE", DEFAULT_PROMPT_FILE))
    answer_path = Path(os.environ.get("CAMERA_ANSWER_FILE", DEFAULT_ANSWER_FILE))
    image_dir = Path(os.environ.get("CAMERA_IMAGE_DIR", DEFAULT_IMAGE_DIR))
    endpoint_url = os.environ.get(
        "EDGE_SERVER_CAMERA_URL",
        "http://127.0.0.1:8888/v1/camera/chat/completions",
    )
    model_name = os.environ.get("EDGE_SERVER_MODEL", "auto")
    system_instruction = os.environ.get(
        "EDGE_SERVER_SYSTEM_INSTRUCTION",
        DEFAULT_SYSTEM_INSTRUCTION,
    )
    max_tokens = int(os.environ.get("EDGE_SERVER_MAX_TOKENS", "128"))
    if len(sys.argv) > 1:
        prompt = " ".join(sys.argv[1:]).strip()
    else:
        if not prompt_path.is_file():
            print(f"prompt file not found: {prompt_path}", file=sys.stderr)
            return 1
        prompt = prompt_path.read_text(encoding="utf-8").strip()

    payload = {
        "model": model_name,
        "prompt": prompt,
        "system_instruction": system_instruction,
        "capture_mode": "latest",
        "max_tokens": max_tokens,
        "return_image": True,
    }

    request = urllib.request.Request(
        endpoint_url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            status_code = response.status
            response_body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", errors="replace")
        print(f"status: {exc.code}", file=sys.stderr)
        print(error_body, file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"request failed: {exc}", file=sys.stderr)
        return 1

    try:
        response_json = json.loads(response_body)
    except json.JSONDecodeError as exc:
        print(f"invalid response JSON: {exc}", file=sys.stderr)
        return 1

    text = response_json.get("text", "")
    if not isinstance(text, str):
        text = json.dumps(text, ensure_ascii=False, indent=2)
    answer_path.write_text(text, encoding="utf-8")

    print(f"status: {status_code}")
    print(f"text: {text}")
    print(f"inference_time_ms: {response_json.get('inference_time_ms', '')}")
    print(f"timestamp_ms: {response_json.get('timestamp_ms', '')}")
    print(
        "image: "
        + f"{response_json.get('image_width', '')}x{response_json.get('image_height', '')}"
    )
    print(f"model: {response_json.get('model', '')}")
    image_base64 = response_json.get("image_base64")
    if isinstance(image_base64, str) and image_base64:
        image_format = str(response_json.get("image_format") or "jpg").lower()
        extension = "jpg" if image_format == "jpeg" else image_format
        timestamp_ms = response_json.get("timestamp_ms") or "camera"
        image_dir.mkdir(parents=True, exist_ok=True)
        image_path = image_dir / f"{timestamp_ms}.{extension}"
        image_path.write_bytes(base64.b64decode(image_base64))
        print(f"saved_image: {image_path}")
    print(f"saved_answer: {answer_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
