# NEXUS AI

**Your AI. Your device. Your data.**

NEXUS AI is an Android-first, on-device AI chat app built with Kotlin, Jetpack Compose and llama.cpp. It runs supported GGUF models locally and includes a permission-scoped workspace foundation.

## V0.9.1 — Safer Android model loading

- Reduce native inference context from 2048 to 1024 tokens and batch size from 64 to 32 to lower peak RAM use
- Check available system RAM before loading large GGUF models and show a clear recovery message rather than entering a likely low-memory crash
- Recommend smaller 1B / 1.5B models when a 3B model cannot be safely loaded

## V0.9.0 — Connected local workspace agent

- Updated on-device system identity and workspace capability instructions
- Chat generation can request the existing MCP-compatible workspace list/read/write/delete tools
- Tool requests are parsed and executed locally with a bounded three-call loop
- Read-only list/read tools are enabled by default; write/delete remain opt-in and require explicit per-action confirmation
- Tool execution respects the user's existing workspace folder selection and per-tool permissions
- Tool errors and permission denials are returned to the model instead of being reported as successful actions

## V0.8.1 — Model reuse and voice assistant

- Reuse the resident native model when returning to the chat screen, avoiding redundant model reloads while the Android process remains alive
- Pass the selected model into Voice mode and use Android on-device speech recognition when the device supports it
- Send recognized speech to the local inference engine and display the transcript and generated response
- Optional spoken response through the installed Android text-to-speech engine
- Microphone permission handling and clear unavailable-service/error states

## V0.8.0 — Local chat history and voice animation

- Local conversation history with reopen and context restoration
- Native Compose particle-sphere voice visualizer based on the supplied HTML reference
- Interactive 1,800-dot sphere with idle, listening, thinking, and speaking animation states

## V0.7.1 — Chat-first interface and local inference

- Chat-first interface based on the NEXUS reference design: model selector, overflow menu, empty state, message composer, model sheet, and settings sheet\n- Model switching unloads the previous native model before loading another
- New chat and Clear all chats reset the native conversation history and KV cache
- Failed prompt processing restores a clean conversation state
- Default context window: 2048 tokens
- Light, dark, and system themes\n- Wi-Fi-only download preference\n- Last-used model selection is restored on launch\n- Kotlin + Jetpack Compose Android app
- Curated GGUF catalogue: Llama 3.2 1B, Qwen2.5 1.5B, Qwen2.5 3B
- Model downloads through Android DownloadManager
- Import GGUF files through Android's Storage Access Framework
- App-specific model storage and deletion
- User-selected workspace folder through Android's Storage Access Framework
- llama.cpp-backed local GGUF inference and streaming chat\n- MCP JSON-RPC core methods: initialize, ping, tools/list, tools/call
- Workspace tools: list, read, write, and delete files
- Local HTML preview in a WebView without a native JavaScript bridge

**Honest milestone boundary:** local chat is implemented, but the MCP workspace registry is still an in-process MCP-compatible tool layer. It is not connected to the model's generation loop and is not an external network MCP server. The model cannot yet autonomously decide which tools to call.

## Build requirements

- Android Studio (recent stable release)
- JDK 17
- Android SDK Platform 35
- Android Gradle Plugin 8.7.3
- Gradle 8.9

Open the repository in Android Studio and sync Gradle, or run:

    gradle :app:assembleDebug

Debug APK output:

    app/build/outputs/apk/debug/app-debug.apk

GitHub Actions builds the debug APK on pushes to main and manual dispatches, then uploads it as an artifact.

## MCP workspace security

- NEXUS can access only the folder explicitly selected by the user.
- File operations are restricted to files directly inside that folder; path traversal and nested paths are rejected.
- Text file tools have a 1 MB size limit.
- HTML preview runs in a WebView with file/content access disabled and no native JavaScript bridge.
- The app asks for confirmation before overwriting an existing file. MCP JSON-RPC calls cannot self-approve overwrites or deletion; only trusted host UI code can pass the approval flag. A delete confirmation UI is still to be added.
- This milestone does not provide arbitrary shell access, general Python execution, remote MCP server connections, or unrestricted device control. HTML and its JavaScript can be previewed inside the app's WebView.

## Architecture

    Local model (next milestone)
       |
    Agent tool-call loop (next milestone)
       |
    MCP-compatible tool registry
       |
    Workspace permission boundary (user-selected folder)
       |
    list_files / read_file / write_file / delete_file
       |
    HTML preview in isolated WebView

## Roadmap

- [x] Android project and GGUF model manager
- [x] MCP-compatible workspace tool registry
- [x] llama.cpp native runtime and offline chat
- [ ] Agent tool-call loop, approvals, and execution trace
- [ ] Sandboxed code runner and local document RAG
- [ ] Remote MCP server connections (Streamable HTTP)
- [ ] Identity, permissions, guardrails, and app actions
- [ ] Caching, routing, observability, and evaluations

Application source code is intended to be MIT-licensed. Third-party model weights remain subject to their own licences and terms.
