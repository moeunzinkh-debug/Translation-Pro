#!/usr/bin/env python3
"""
Offline verification of the SSE streaming parser added to
TranslationRepository.streamGeminiInteraction().

This is a faithful port of the Kotlin logic:

    val source = body.source()
    var eventName = ""
    while (!source.exhausted()) {
        val line = source.readUtf8Line() ?: break
        if (line.isEmpty()) { eventName = ""; continue }
        if (line.startsWith(":")) continue
        if (line.startsWith("event:")) { eventName = line.removePrefix("event:").trim(); continue }
        if (!line.startsWith("data:")) continue
        val payload = line.removePrefix("data:").trim()
        if (payload == "[DONE]") break
        if (eventName == "interaction.completed") break
        val chunk = extractTextDelta(payload) ?: continue
        accumulated.append(chunk)
        onPartial(accumulated.toString())
    }

Event shapes are taken from Google's documented Interactions API SSE stream
(ai.google.dev/gemini-api/docs/interactions-breaking-changes-may-2026).
"""

import json

INTERACTION_COMPLETED = "interaction.completed"


def extract_text_delta(payload: str):
    """Port of extractTextDelta(). Returns visible text, never private reasoning."""
    try:
        obj = json.loads(payload)
    except Exception:
        return None
    delta = obj.get("delta")
    if not isinstance(delta, dict):
        return None
    # `thought` deltas are the model's private reasoning and must never be shown.
    if str(delta.get("type", "")).lower() != "text":
        return None
    text = delta.get("text")
    return text if text else None


def run_stream(raw: str):
    """Port of streamGeminiInteraction()'s read loop. Returns (text, stop_reason, deltas)."""
    accumulated = []
    deltas = []
    event_name = ""
    stop_reason = "socket-closed"

    for line in raw.split("\n"):
        if line == "":
            event_name = ""
            continue
        if line.startswith(":"):
            continue
        if line.startswith("event:"):
            event_name = line[len("event:"):].strip()
            continue
        if not line.startswith("data:"):
            continue

        payload = line[len("data:"):].strip()
        if payload == "[DONE]":
            stop_reason = "[DONE] sentinel"
            break
        if event_name == INTERACTION_COMPLETED:
            stop_reason = "interaction.completed (early exit, no socket wait)"
            break

        chunk = extract_text_delta(payload)
        if chunk is None:
            continue
        accumulated.append(chunk)
        deltas.append("".join(accumulated))

    return "".join(accumulated), stop_reason, deltas


# ── Test 1: canonical happy path, with thought steps interleaved ────────────────────
HAPPY_PATH = """event: interaction.created
data: {"interaction":{"id":"int_xyz","status":"in_progress","object":"interaction","model":"gemini-3.7-flash"},"event_type":"interaction.created"}

event: step.start
data: {"index":0,"step":{"type":"thought","signature":"abc123..."},"event_type":"step.start"}

event: step.delta
data: {"index":0,"delta":{"type":"thought","text":"User wants a greeting."},"event_type":"step.delta"}

event: step.stop
data: {"index":0,"event_type":"step.stop"}

event: step.start
data: {"index":1,"step":{"content":[{"text":"Xin","type":"text"}],"type":"model_output"},"event_type":"step.start"}

event: step.delta
data: {"index":1,"delta":{"type":"text","text":"Xin"},"event_type":"step.delta"}

event: step.delta
data: {"index":1,"delta":{"type":"text","text":" chào"},"event_type":"step.delta"}

event: step.start
data: {"index":2,"step":{"type":"google_search_call"},"event_type":"step.start"}

event: step.delta
data: {"index":1,"delta":{"text":"no-type-field"},"event_type":"step.delta"}

event: step.stop
data: {"index":1,"event_type":"step.stop"}

event: interaction.completed
data: {"type":"interaction.completed","interaction":{"id":"int_xyz","status":"completed","usage":{"total_tokens":79}},"event_type":"interaction.completed"}

data: {"type":"interaction.completed","usage":{"total_tokens":79}}"""


# ── Test 2: keep-alive comments and id:/retry: noise ────────────────────────────────
NOISY_STREAM = """: keep-alive
id: 42
retry: 3000
event: step.delta
data: {"index":1,"delta":{"type":"text","text":"សួស្តី"},"event_type":"step.delta"}

event: step.delta
data: {"index":1,"delta":{"type":"text","text":" មករួច"},"event_type":"step.delta"}

event: interaction.completed
data: {"interaction":{"status":"completed"}}"""


# ── Test 3: server ignores `stream` and returns plain JSON (no SSE frames) ──────────
NON_SSE_FALLBACK = """{
  "id": "int_1",
  "status": "completed",
  "steps": [
    {"type":"model_output","content":[{"type":"text","text":"Chào bạn"}]}
  ]
}"""


def main():
    failures = []

    def check(name, cond, detail=""):
        print(f"{'PASS' if cond else 'FAIL'}  {name}")
        if not cond:
            failures.append(f"{name} {detail}")

    # 1. Happy path
    text, stop, deltas = run_stream(HAPPY_PATH)
    check("assembles streamed tokens", text == "Xin chào", f"got {text!r}")
    check("never leaks thought text", "User wants a greeting" not in text, f"got {text!r}")
    check("skips untagged delta (no type field)", "no-type-field" not in text, f"got {text!r}")
    check("exits on interaction.completed", "interaction.completed" in stop, f"got {stop!r}")
    check("emits incremental partials", deltas == ["Xin", "Xin chào"], f"got {deltas!r}")

    # 2. Noisy stream
    text2, stop2, _ = run_stream(NOISY_STREAM)
    check("ignores comments/id/retry lines", text2 == "សួស្តី មករួច", f"got {text2!r}")
    check("noisy stream also exits early", "interaction.completed" in stop2, f"got {stop2!r}")

    # 3. Non-SSE response -> must yield nothing, triggering the non-streaming fallback
    text3, _, _ = run_stream(NON_SSE_FALLBACK)
    check("non-SSE body yields empty text -> fallback", text3 == "", f"got {text3!r}")

    # 4. Khmer/emoji passthrough
    text4, _, _ = run_stream(
        'event: step.delta\ndata: {"delta":{"type":"text","text":"ស្វាគមន៍"}}\n\n'
        'event: step.delta\ndata: {"delta":{"type":"text","text":" 🙌"}}\n\n'
        'event: interaction.completed\ndata: {}\n'
    )
    check("unicode passthrough", text4 == "ស្វាគមន៍ 🙌", f"got {text4!r}")

    print()
    if failures:
        print(f"{len(failures)} CHECK(S) FAILED")
        raise SystemExit(1)
    print("All SSE parser checks passed.")


if __name__ == "__main__":
    main()
