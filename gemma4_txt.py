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
    thinking_env = os.environ.get("EDGE_SERVER_THINKING")
    max_tokens_env = os.environ.get("EDGE_SERVER_MAX_TOKENS")
    top_k_env = os.environ.get("EDGE_SERVER_TOPK")
    top_p_env = os.environ.get("EDGE_SERVER_TOPP")
    temperature_env = os.environ.get("EDGE_SERVER_TEMPERATURE")

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
    if thinking_env is not None:
        payload["thinking"] = thinking_env.lower() in {"1", "true", "on", "yes"}
    if max_tokens_env is not None:
        payload["max_tokens"] = int(max_tokens_env)
    if top_k_env is not None:
        payload["top_k"] = int(top_k_env)
    if top_p_env is not None:
        payload["top_p"] = float(top_p_env)
    if temperature_env is not None:
        payload["temperature"] = float(temperature_env)

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
