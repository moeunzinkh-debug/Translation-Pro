# Translate Pro — Smart AI Translator for Android

**Translate Pro** is a modern, culturally aware Android translation application powered by Jetpack Compose and Material 3. It translates text and subtitle files (`.srt`, `.vtt`) with slang and idiom understanding while offering multi-provider API key management.

---

## Features

1. **Smart Text Translation (Instant Mode)**
   - **Streamed output** — the translation paints in token by token, so the first words appear
     in milliseconds instead of after the whole answer.
   - **Pinned low thinking** — Gemini 3.x models are hybrid reasoning models that emit thought
     tokens *before* answering and default to a high thinking level. The app pins
     `thinking_level` to `minimal` (or `low` on 3.7/3.8, which reject `minimal`) and disables
     thought summaries. This is why translating even a single word used to feel slow: the cost
     was per-request overhead, not per-word.
   - **No doomed retries** — deterministic failures (400 / 401 / 403 / 404) now fail
     immediately instead of burning a second request plus a 1s sleep.
   - Multiline input & output text areas with copy/share buttons.
   - Source language auto-detection and 30+ target languages with quick swap.
   - Smart prompt logic instructing AI models to translate slang, idioms, and cultural nuance rather than word-for-word.
   - Preserves tone (Natural, Formal, Casual) and displays cultural/idiom explanatory notes when applicable.

2. **Multiple AI Provider Keys & Provider Selection**
   - Editable API keys stored locally using **EncryptedSharedPreferences (AES-256 GCM)**.
   - Provider Selection:
     - **Sea-Lion AI** (Default — regional LLM tailored for Southeast Asian languages)
     - **Google Gemini** (Supports runtime `.env` injection via `GEMINI_API_KEY` or custom key)
     - **OpenAI ChatGPT** (GPT-4o / GPT-4o-mini)
     - **Custom Endpoint** (Configurable Base URL and model)
   - Gemini uses Google's current **Interactions API**, with `gemini-3.7-flash` as the default
     (the fastest model in the 3.5–3.8 Flash line).
   - The Gemini model picker loads every compatible model live from Google's paginated Models
     API, so users can choose a model without waiting for an app update. Models are sorted
     fastest-first: `gemini-3.7-flash` → `3.6` → `3.5` → `3.8` → `3.5-flash-lite` → `3.1-flash-lite`.
   - Non-text models (embedding, image, video, TTS, live) are filtered out of the picker.
   - Built-in "Test API Connection" tool to verify credentials.

3. **Gemini Key Pool & Daily Budget**
   - Add and remove an unlimited number of encrypted Gemini API keys.
   - Select an active key and see its app-managed daily request budget and remaining requests.
   - Gemini does not provide a remaining project quota endpoint for API keys; the displayed amount is the transparent local request budget configured for that key.

4. **Gemini Audio Transcript**
   - Select an audio file and generate a plain-text transcript through Gemini's multimodal API.
   - Copy the completed transcript directly from the app.

5. **Media Subtitle Translation (.srt & .vtt)**
   - Parses SubRip (`.srt`) and WebVTT (`.vtt`) subtitle files.
   - Preserves exact index numbers and timing timestamps (`00:01:20,000 --> 00:01:23,150`).
   - Batch chunking strategy with real-time progress bar and automatic single-line fallback retries.
   - Live segment preview table showing original vs translated dialogue.
   - **Download as SRT** — exports translated subtitles in standard SubRip format.
   - **Download as TXT** — exports only the translated dialogue text (no timecodes or indices).
   - Export in original format and share translated subtitle files.

6. **Gemini API Key Auto-Rotation**
   - Add and manage an unlimited number of encrypted Gemini API keys.
   - **Automatic key rotation**: when a key hits rate limits (HTTP 429) or becomes invalid (HTTP 403/401), the app automatically switches to the next available key with remaining budget.
   - Up to 3 key rotation attempts per request for seamless failover.
   - Default key seeded on first launch for instant out-of-the-box Gemini support.
   - Works for both translation and audio transcription requests.

---

## How to Set Up & Configure Sea-Lion API

1. Open **Translate Pro**.
2. Navigate to the **Settings** tab at the bottom right.
3. Select **Sea-Lion AI** from the Active AI Provider dropdown.
4. Input your Sea-Lion API Key / Token.
5. Set the Base URL (Default: `https://api.sea-lion.ai/v1/`).
6. Set the Model Name (Default: `aisingapore/sea-lion-7b-instruct`).
7. Tap **Test API Connection** to verify your configuration.

---

## How to Test Translation & Subtitles

### Testing Text Translation
1. Go to the **Text** tab.
2. Select your Source ("Auto-detect" or specific language) and Target Language.
3. Tap "Try sample: Idiom" or "Try sample: Slang" or type custom text (e.g., *"Break a leg on your test today!"*).
4. Tap **Translate with Sea-Lion AI**.
5. View the natural translation and cultural note box. Tap **Copy** to copy the result.

### Testing Subtitle Translation (.srt / .vtt)
1. Go to the **Subtitles** tab.
2. Tap **Try Sample** to load a built-in subtitle file or tap **Open File** to pick a `.srt` or `.vtt` file from your device.
3. Choose your Target Language and Batch Chunk Size (5, 8, 10, or 15).
4. Tap **Start Subtitle Translation**.
5. Watch the live progress percentage and preview table update segment by segment.
6. After translation completes, choose your export format:
   - **Download as SRT** — standard SubRip subtitle file with timecodes.
   - **Download as TXT** — plain text with only translated dialogue lines.
   - **Export (original format)** — saves in the same format as the source file.
   - **Share** — share the translated file via any installed app.

---

## Security & Storage
- API keys are saved on the device via `EncryptedSharedPreferences` backed by the Android KeyStore (`MasterKey.Builder`). Keys are never sent to third-party tracking servers.
- The HTTP logger is restricted to `BuildConfig.DEBUG` and redacts `Authorization` / `x-goog-api-key`, so keys are never written to logcat in release builds.
- The decrypted key pool is cached in memory (and written through on every change) so a translation does not re-run Tink AES-SIV/AES-GCM decryption several times per request.

---

## Build & Requirements
- Minimum SDK: `26` (Android 8.0)
- Target SDK: `34` / `36`
- Architecture: Kotlin, Jetpack Compose, MVVM, Retrofit + OkHttp + Moshi, Coroutines, Material 3.

### Building a release APK

```bash
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Requires a JDK 17+ and the Android SDK (set `ANDROID_HOME` or `sdk.dir` in `local.properties`).
R8 minification and resource shrinking are enabled for release.

**Signing:** if `my-upload-key.jks` exists in the project root *and* `STORE_PASSWORD` +
`KEY_PASSWORD` are exported, the build is signed with your upload key. Otherwise it falls back
to AGP's built-in debug signing config (auto-generated at `~/.android/debug.keystore`) so
`assembleRelease` still produces an installable APK instead of failing on a missing keystore.

```bash
export STORE_PASSWORD=...
export KEY_PASSWORD=...
export KEY_ALIAS=upload            # optional, defaults to "upload"
export KEYSTORE_PATH=/path/to.jks  # optional, defaults to ./my-upload-key.jks
./gradlew :app:assembleRelease
```
