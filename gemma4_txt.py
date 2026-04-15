#!/usr/bin/env python3

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path


def main() -> int:
    prompt_path = Path(os.environ.get("PROMPT_FILE", "prompt.txt"))
    answer_path = Path(os.environ.get("ANSWER_FILE", "answer.txt"))
    endpoint_url = os.environ.get(
        "EDGE_SERVER_URL",
        "http://127.0.0.1:8888/v1/chat/completions",
    )
    model_name = os.environ.get("EDGE_SERVER_MODEL", "auto")

    if not prompt_path.is_file():
        print(f"prompt file not found: {prompt_path}", file=sys.stderr)
        return 1

    prompt_text = prompt_path.read_text(encoding="utf-8")
    payload = {
        "model": model_name,
        "stream": False,
        "messages": [
            {
                "role": "user",
                "content": prompt_text,
            }
        ],
    }

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
