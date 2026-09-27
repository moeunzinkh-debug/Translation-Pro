#!/usr/bin/env python3
"""
Measure Gemini "instant mode" latency for the exact request shape Translate Pro sends.

This answers the question the sandbox cannot: is the change actually faster, and which
thinking_level is valid for which model?

Usage:
    export GEMINI_API_KEY=your-key
    python3 tools/bench_gemini_latency.py

    # optional
    export BENCH_TEXT="Hi"
    export BENCH_REPEATS=3

For each model it sends the SAME request three ways:

  1. no generation_config          -> Gemini 3.x default thinking level (what the app did before)
  2. thinking_level: minimal       -> what the app now sends for 3.5 / 3.6
  3. thinking_level: low           -> what the app now sends for 3.7 / 3.8

It reports time-to-first-byte (TTFB) and total time, because TTFB is the number the user
actually feels. A 400 response is reported as such rather than treated as a failure: models
that reject a given thinking level are exactly what we want to discover.
"""

import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request

ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"

MODELS = [
    "gemini-3.5-flash",
    "gemini-3.6-flash",
    "gemini-3.7-flash",
    "gemini-3.8-flash",
]

MODES = [
    ("default (no override)", None),
    ("minimal", "minimal"),
    ("low", "low"),
]

SYSTEM_PROMPT = (
    "You are a high-precision smart translator specialized in natural idioms and slang.\n"
    "Task:\n"
    "1. Translate text from Auto-detect to Khmer (Cambodian).\n"
    "2. Understand slang, idioms, metaphors, and cultural context.\n"
    "3. Tone instruction: Match the exact tone (formal or casual) of the original text.\n"
    "4. OUTPUT FORMAT: Return the translated text directly. Do not add introductory labels.\n"
    "5. SPEED: Answer immediately with the translation. Never restate the task."
)


def build_body(model: str, thinking_level: str | None, text: str) -> dict:
    generation_config: dict = {"thinking_summaries": "none", "max_output_tokens": 1024}
    if thinking_level is not None:
        generation_config["thinking_level"] = thinking_level
    return {
        "model": model,
        "input": [{"type": "text", "text": text}],
        "system_instruction": SYSTEM_PROMPT,
        "generation_config": generation_config,
        "store": False,
    }


def call(api_key: str, model: str, thinking_level, text: str, stream: bool = True):
    """Returns (ttfb_seconds, total_seconds, result_text, error_or_None)."""
    body = build_body(model, thinking_level, text)
    if stream:
        body["stream"] = True

    request = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "x-goog-api-key": api_key,
            "Content-Type": "application/json",
        },
        method="POST",
    )

    ctx = ssl.create_default_context()
    start = time.perf_counter()
    ttfb = None
    collected: list[str] = []
    completed_early = False

    try:
        with urllib.request.urlopen(request, timeout=90, context=ctx) as response:
            for raw_line in response:
                if ttfb is None and raw_line.strip().startswith(b"data:"):
                    ttfb = time.perf_counter() - start
                line = raw_line.decode("utf-8", "replace").rstrip("\n")
                if not line.startswith("data:"):
                    continue
                payload = line[len("data:"):].strip()
                if payload == "[DONE]":
                    completed_early = True
                    break
                try:
                    delta = json.loads(payload).get("delta")
                except Exception:
                    continue
                # `thought` deltas are private reasoning; never count them as output.
                if isinstance(delta, dict) and delta.get("type") == "text" and delta.get("text"):
                    collected.append(delta["text"])
                    if not completed_early:
                        continue
        total = time.perf_counter() - start
        return ttfb, total, "".join(collected).strip(), None
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        return None, time.perf_counter() - start, None, f"HTTP {e.code}: {detail}"
    except Exception as e:
        return None, time.perf_counter() - start, None, f"{type(e).__name__}: {e}"


def main() -> int:
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        print("Set GEMINI_API_KEY first:\n  export GEMINI_API_KEY=your-key")
        return 2

    text = os.environ.get("BENCH_TEXT", "Hi")
    repeats = int(os.environ.get("BENCH_REPEATS", "3"))

    print(f"text    : {text!r}")
    print(f"repeats : {repeats} (best-of is reported; network jitter is real)")
    print()
    header = f"{'model':<20} {'mode':<22} {'TTFB':>9} {'total':>9}  result"
    print(header)
    print("-" * len(header))

    for model in MODELS:
        for label, level in MODES:
            best_ttfb = best_total = None
            sample = None
            error = None

            for _ in range(repeats):
                ttfb, total, text_out, err = call(api_key, model, level, text)
                if err:
                    error = err
                    continue
                sample = text_out
                if best_ttfb is None or (ttfb is not None and ttfb < best_ttfb):
                    best_ttfb = ttfb
                if best_total is None or total < best_total:
                    best_total = total

            if error:
                print(f"{model:<20} {label:<22} {'-':>9} {'-':>9}  {error[:70]}")
            else:
                ttfb_s = f"{best_ttfb*1000:.0f}ms" if best_ttfb else "n/a"
                total_s = f"{best_total*1000:.0f}ms" if best_total else "n/a"
                preview = (sample or "")[:40].replace("\n", " ")
                print(f"{model:<20} {label:<22} {ttfb_s:>9} {total_s:>9}  {preview!r}")
        print()

    print("A 400 on 'minimal' is expected and harmless: 3.7/3.8 Flash reject it.")
    print("The app already handles that — it drops the override and retries once.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
