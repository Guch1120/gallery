#!/usr/bin/env python3

import base64
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_IMAGE_PATH = "test.png"


def main() -> int:
    prompt_path = Path(os.environ.get("PROMPT_FILE", "prompt.txt"))
    answer_path = Path(os.environ.get("ANSWER_FILE", "answer.txt"))
    endpoint_url = os.environ.get(
        "EDGE_SERVER_URL",
        "http://127.0.0.1:8888/v1/chat/completions",
    )
    model_name = os.environ.get("EDGE_SERVER_MODEL", "auto")
    thinking_env = os.environ.get("EDGE_SERVER_THINKING")
    # 既定の画像ファイルは DEFAULT_IMAGE_PATH で指定する。
    # 毎回別の画像を使いたい場合は、実行時に
    # `python3 gemma4_image.py /path/to/image.png`
    # のように第1引数で画像パスを渡す。
    # デフォルト画像そのものを変更したい場合は、
    # このファイル上部の DEFAULT_IMAGE_PATH を書き換える。
    image_path = Path(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_IMAGE_PATH)

    if not prompt_path.is_file():
        print(f"prompt file not found: {prompt_path}", file=sys.stderr)
        return 1

    if not image_path.is_file():
        print(f"image file not found: {image_path}", file=sys.stderr)
        print("usage: python3 gemma4_image.py [image_path]", file=sys.stderr)
        return 1

    prompt_text = prompt_path.read_text(encoding="utf-8")
    image_bytes = image_path.read_bytes()
    mime_type, _ = mimetypes.guess_type(str(image_path))
    if mime_type is None:
        mime_type = "application/octet-stream"

    payload = {
        "model": model_name,
        "stream": False,
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": prompt_text,
                    },
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": "data:"
                            + mime_type
                            + ";base64,"
                            + base64.b64encode(image_bytes).decode("ascii"),
                        },
                    },
                ],
            }
        ],
    }
    if thinking_env is not None:
        payload["thinking"] = thinking_env.lower() in {"1", "true", "on", "yes"}

    request = urllib.request.Request(
        endpoint_url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(request) as response:
            response_body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", errors="replace")
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

    choices = response_json.get("choices") or []
    if not choices:
        error = response_json.get("error", {})
        message = error.get("message") or "response does not contain choices"
        print(message, file=sys.stderr)
        return 1

    content = choices[0].get("message", {}).get("content", "")
    if not isinstance(content, str):
        content = json.dumps(content, ensure_ascii=False, indent=2)

    answer_path.write_text(content, encoding="utf-8")
    print(content)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
