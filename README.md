# NEXUS AI

**Your AI. Your device. Your data.**

NEXUS AI is an Android-first, offline-first local AI app. V0.1 establishes the model-management foundation: discover, download, import, and manage quantized GGUF language models on-device.

## V0.1 — Model Manager

- Kotlin + Jetpack Compose Android app
- Curated GGUF catalogue: Llama 3.2 1B, Qwen2.5 1.5B, and Qwen2.5 3B
- In-app downloads through Android DownloadManager
- Download progress and failure reporting
- Import existing GGUF files through Android's document picker
- App-specific model storage and model deletion
- SHA-256 verification for catalogue entries with pinned checksums

**Honest milestone boundary:** V0.1 does not generate AI responses yet. The llama.cpp native inference runtime and offline chat are the next milestone. RAG, MCP tools, identity, guardrails, caching, observability, and evaluations follow after inference works.

## Build requirements

- Android Studio (recent stable release)
- JDK 17
- Android SDK Platform 35
- Android Gradle Plugin 8.7.3
- Gradle 8.9

Open the repository in Android Studio and sync Gradle. Alternatively, run Gradle 8.9 on a machine with the Android SDK installed:

    gradle :app:assembleDebug

The debug APK is generated at:

    app/build/outputs/apk/debug/app-debug.apk

The Android GitHub Actions workflow builds the debug APK on pushes to main and manual dispatches, then uploads it as an artifact.

## Model catalogue

| Model | Quantization | Approx. size |
|---|---|---:|
| Llama 3.2 1B Instruct | Q4_K_M | 0.81 GB |
| Qwen2.5 1.5B Instruct | Q4_K_M | 0.99 GB |
| Qwen2.5 3B Instruct | Q4_K_M | 1.93 GB |

Model files are hosted by third parties on Hugging Face. Review each model card, licence, and usage terms before downloading or redistributing. Downloads are large; Wi-Fi and sufficient free storage are recommended.

## Architecture

    Compose UI
       |
    Model catalogue + model manager
       |
    Android DownloadManager / Storage Access Framework
       |
    App-specific GGUF storage
       |
    Next: llama.cpp Android native runtime
       |
    Later: chat, RAG, MCP tools, security, optimization, evaluations

## Privacy

- Model files are stored in app-specific storage.
- This milestone does not send prompts to a cloud inference service.
- Internet access is used for model downloads.
- Uninstalling the app removes its app-specific model files.

## Roadmap

- [x] V0.1 — Android project and GGUF model manager
- [ ] V0.2 — llama.cpp native runtime and offline chat
- [ ] V0.3 — Model metadata, device guidance, and context controls
- [ ] V0.4 — Local document RAG
- [ ] V0.5 — Agent tools and MCP integration
- [ ] V0.6 — Identity, permissions, and guardrails
- [ ] V0.7 — Caching, routing, observability, and evaluations

Application source code is intended to be MIT-licensed. Third-party model weights remain subject to their own licences and terms.
