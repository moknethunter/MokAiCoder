# MokAiCoder for Android

Both editions support Android 8.0 and later. The bundled local GGUF runtime in the full edition targets ARM64.

[Arabic help guide](../docs/help.ar.md) · [Android workflow builds](https://github.com/moknethunter/MokAiCoder/actions/workflows/android.yml)

## Voice and documents edition

This edition opens directly to local voice and document tools. Its package ID is `org.mokai.coder.tools`.

- **Dictation.** Download the [Vosk Arabic model](https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip), then import the ZIP from the voice tool. Start dictation and grant microphone access. You can save the transcription as TXT.
- **Text-to-speech.** Install an offline Arabic voice in the Android text-to-speech settings. Enter text in MokAiCoder and tap the read button.
- **Documents.** Enter or paste text. Use `##` for headings and `-` for list items. Preview the result and save it as Word or use the system print screen to save a PDF.

## Full edition

- **Hosted chat and project assistant.** In Connection, select the MokAiCoder server and enter its HTTPS address and access token. The server can provide Grok, OpenRouter, or Ollama. Open **Project Assistant** to review and propose changes across a configured GitHub repository with Grok.
- **Local model manager.** Open **Phone Models** or enter `نزّل نموذج Qwen3-0.6B` in chat. The app checks the phone's architecture, available memory, and storage before downloading a supported catalog model. It verifies the file checksum and registers it for use. Android continues downloads in the background and the app checks their status when reopened.
- **Model import and selection.** Import a local text GGUF compatible with llama.cpp. Choose a model explicitly or let the app select a downloaded coding or general model. It loads one model at a time and accepts files up to approximately 2.73 GiB, subject to available resources.
- **Local interface designer.** Use the tools menu to preview a responsive layout and export it as HTML.

## Build an APK

Install a JDK, Android SDK platform 35, Build Tools 35.0.0, `zip`, `unzip`, and `curl`. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT`. Set `MOKAI_ANDROID_KEYSTORE` to a signing keystore and `MOKAI_ANDROID_KS_PASS` to its password. The default signing alias is `mokai` and can be changed with `MOKAI_ANDROID_KEY_ALIAS`.

From the repository root:

```sh
MOKAI_ANDROID_EDITION=tools ./android/build.sh android/build/MokAiCoder-Voice-Docs.apk
MOKAI_ANDROID_BUILD_LOCAL_MODELS=1 ./android/build.sh android/build/MokAiCoder-Assistant.apk
```

Install Android NDK 28.2.13676358, CMake, and Ninja for the full build with local models enabled. The script retrieves pinned Vosk and JNA dependencies. The native build uses a pinned llama.cpp revision.

GitHub Actions signs previews with a per-run key. The full preview uses `org.mokai.coder.assistant.preview`. To distribute an update over an existing installation, build with the same package ID and signing key as that installation.
