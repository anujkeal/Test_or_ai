package com.keal.nexus.agent

/**
 * Lightweight deterministic request classifier. It does not execute actions;
 * it labels the request so the host app can choose the correct permissioned path.
 */
enum class NexusIntentType {
    MEMORY_MANAGEMENT,
    MEMORY_SAVE,
    DEVICE_INFORMATION,
    PHONE_ACTION,
    CODE_EXECUTION,
    WORKSPACE,
    DOCUMENT_QUESTION,
    GENERAL
}

data class NexusIntent(val type: NexusIntentType, val confidence: Float, val reason: String)

object NexusIntentRouter {
    fun classify(prompt: String): NexusIntent {
        val q = prompt.trim().lowercase()
        if (q.isBlank()) return NexusIntent(NexusIntentType.GENERAL, 0f, "Empty request")

        fun match(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(q)
        return when {
            match("""\b(show|list|what do you remember|forget|delete|clear|erase)\b.{0,35}\b(memories|memory|my name|everything|all)\b""") ->
                NexusIntent(NexusIntentType.MEMORY_MANAGEMENT, 0.98f, "Explicit memory-management language")
            match("""\b(save this|remember this|remember that|save that|don't forget)\b""") ->
                NexusIntent(NexusIntentType.MEMORY_SAVE, 0.98f, "Explicit memory-save language")
            match("""\b(what|which|tell me|show|get)\b.{0,35}\b(phone|device|mobile)\b.{0,30}\b(model|name|number|version|storage|battery)\b""") ||
                match("""\b(device model|phone model|android version|battery level|free storage)\b""") ->
                NexusIntent(NexusIntentType.DEVICE_INFORMATION, 0.94f, "Device-information question")
            match("""\b(open|launch|call|dial|turn on|turn off|enable|disable|scroll|tap|press)\b.{0,50}\b(app|phone|camera|flashlight|wifi|bluetooth|settings|youtube|whatsapp|contact|screen)\b""") ->
                NexusIntent(NexusIntentType.PHONE_ACTION, 0.88f, "Potential Android action")
            match("""\b(run|execute|debug)\b.{0,45}\b[\w-]+\.(py|js|ts|kt|java|cpp|go|rs|rb|php|sh)\b""") ->
                NexusIntent(NexusIntentType.CODE_EXECUTION, 0.98f, "Explicit source-file execution")
            match("""\b(workspace|file|folder|create|write|delete|read|list)\b.{0,80}\b(file|folder|workspace|\.py|\.kt|\.java|\.txt|\.md|\.html|\.json)\b""") ->
                NexusIntent(NexusIntentType.WORKSPACE, 0.82f, "Workspace or file operation")
            match("""\b(document|pdf|docx|uploaded file|attached file)\b""") ->
                NexusIntent(NexusIntentType.DOCUMENT_QUESTION, 0.82f, "Document-related question")
            else -> NexusIntent(NexusIntentType.GENERAL, 0.6f, "No deterministic capability matched")
        }
    }

    fun modelGuidance(intent: NexusIntent): String = when (intent.type) {
        NexusIntentType.GENERAL -> ""
        NexusIntentType.MEMORY_MANAGEMENT -> "Request type: memory management. Use saved local memory only; never invent stored facts."
        NexusIntentType.MEMORY_SAVE -> "Request type: save memory. Only report a save when the host confirms it."
        NexusIntentType.DEVICE_INFORMATION -> "Request type: device information. Use Android-provided facts; do not guess hardware details."
        NexusIntentType.PHONE_ACTION -> "Request type: phone action. Use only the host's allowlisted Android actions and required permissions."
        NexusIntentType.CODE_EXECUTION -> "Request type: code execution. Execute only the named file through the configured code runner."
        NexusIntentType.WORKSPACE -> "Request type: workspace operation. Use only the user-selected folder and permissioned workspace tools."
        NexusIntentType.DOCUMENT_QUESTION -> "Request type: document question. Ground answers in extracted document text when a document is attached."
    }
}
