from __future__ import annotations

import json
import urllib.error
import urllib.request

from config import LLM_API_KEY, LLM_BASE_URL, LLM_MODEL, LLM_TIMEOUT_SECONDS


def chat_completion(messages: list[dict], max_tokens: int = 1400) -> dict:
    url = LLM_BASE_URL.rstrip("/") + "/chat/completions"
    payload = {
        "model": LLM_MODEL,
        "messages": messages,
        "max_tokens": max_tokens,
        "temperature": 0.01,
        "top_p": 0.95,
        "top_k": 20,
        "frequency_penalty": 0.0,
        "presence_penalty": 0.0,
        "repetition_penalty": 1.0,
        "chat_template_kwargs": {"enable_thinking": False}
    }
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {LLM_API_KEY}"
        },
        method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=LLM_TIMEOUT_SECONDS) as response:
            body = json.loads(response.read().decode("utf-8"))
        content = body["choices"][0]["message"]["content"]
        return {"ok": True, "content": content, "raw": body, "used_tool": "llm"}
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, KeyError, json.JSONDecodeError) as exc:
        return {
            "ok": False,
            "content": "",
            "error": str(exc),
            "used_tool": "llm"
        }


def strip_thinking_text(text: str) -> str:
    markers = [
        "\n# ",
        "\n【",
        "\n一、",
        "\n1.",
        "\n1、"
    ]
    lower_markers = ["Thinking Process:", "思考过程", "<think>"]
    if not any(marker in text for marker in lower_markers):
        return text.strip()
    for marker in markers:
        index = text.find(marker)
        if index > 0:
            return text[index:].strip()
    if "</think>" in text:
        return text.split("</think>", 1)[1].strip()
    return text.strip()
