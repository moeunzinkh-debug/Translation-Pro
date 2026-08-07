# Translate Pro — Smart AI Translator for Android

**Translate Pro** is a modern, culturally aware Android translation application powered by Jetpack Compose and Material 3. It translates text and subtitle files (`.srt`, `.vtt`) with slang and idiom understanding while offering multi-provider API key management.

---

## Features

1. **Smart Text Translation**
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
   - Built-in "Test API Connection" tool to verify credentials.

3. **Media Subtitle Translation (.srt & .vtt)**
   - Parses SubRip (`.srt`) and WebVTT (`.vtt`) subtitle files.
   - Preserves exact index numbers and timing timestamps (`00:01:20,000 --> 00:01:23,150`).
   - Batch chunking strategy with real-time progress bar and automatic single-line fallback retries.
   - Live segment preview table showing original vs translated dialogue.
   - Export and share translated subtitle files.

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
6. Tap **Export File** or **Share** to save the translated subtitle file.

---

## Security & Storage
- API keys are saved on the device via `EncryptedSharedPreferences` backed by the Android KeyStore (`MasterKey.Builder`). Keys are never sent to third-party tracking servers.

---

## Build & Requirements
- Minimum SDK: `26` (Android 8.0)
- Target SDK: `34` / `36`
- Architecture: Kotlin, Jetpack Compose, MVVM, Retrofit + OkHttp + Moshi, Coroutines, Material 3.
