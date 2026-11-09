# NEXUS AI

**Your AI. Your device. Your data.**

NEXUS AI is an Android-first local AI project. The current source establishes a GGUF model manager and an MCP-compatible, permission-scoped workspace tool foundation.

## V0.2 — MCP Workspace Foundation

- Kotlin + Jetpack Compose Android app
- Curated GGUF catalogue: Llama 3.2 1B, Qwen2.5 1.5B, Qwen2.5 3B
- Model downloads through Android DownloadManager
- Import GGUF files through Android's Storage Access Framework
- App-specific model storage and deletion
- User-selected workspace folder through Android's Storage Access Framework
- MCP JSON-RPC core methods: initialize, ping, tools/list, tools/call
- Workspace tools: list, read, write, and delete files
- Local HTML preview in a WebView without a native JavaScript bridge

**Honest milestone boundary:** the MCP workspace registry is currently an in-process MCP-compatible tool layer. It is not yet connected to the local model's generation loop and is not an external network MCP server. The app cannot yet autonomously decide which tools to call. The next milestone connects llama.cpp inference to this registry with approval gates for destructive and externally visible actions.

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
- Deleting files and overwriting existing files require confirmation before the future model tool loop executes them.
- This milestone does not provide arbitrary shell access or unrestricted device control.

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
- [ ] llama.cpp native runtime and offline chat
- [ ] Agent tool-call loop, approvals, and execution trace
- [ ] Sandboxed code runner and local document RAG
- [ ] Remote MCP server connections (Streamable HTTP)
- [ ] Identity, permissions, guardrails, and app actions
- [ ] Caching, routing, observability, and evaluations

Application source code is intended to be MIT-licensed. Third-party model weights remain subject to their own licences and terms.
