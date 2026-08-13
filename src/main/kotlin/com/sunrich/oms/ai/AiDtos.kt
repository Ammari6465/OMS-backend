package com.sunrich.oms.ai

/**
 * Ask OMS — request/response contract shared with the Angular copilot.
 *
 * The orchestration layer detects intent, calls existing OMS services for the
 * real data, and returns a minimal structured [context] plus a natural-language
 * [answer]. The `context` is the only thing ever handed to an external LLM.
 */

data class AiChatRequest(
    val query: String? = null,
)

/** Used by the frontend's BackendAiProvider to have the LLM polish a draft. */
data class AiRephraseRequest(
    val query: String? = null,
    val intent: String? = null,
    val context: Map<String, Any?>? = null,
    val draft: String? = null,
)

data class AiAction(
    val kind: String,        // "navigate" | "focus-organogram"
    val label: String,
    val icon: String,
    val route: String? = null,
    val staffId: Long? = null,
)

data class AiChatResponse(
    val intent: String,
    val answer: String,
    val context: Map<String, Any?>,
    val actions: List<AiAction> = emptyList(),
    val tone: String = "normal",   // normal | denied | empty | error
)

data class AiRephraseResponse(
    val answer: String,
)
