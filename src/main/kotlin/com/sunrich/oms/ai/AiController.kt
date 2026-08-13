package com.sunrich.oms.ai

import com.sunrich.oms.common.dto.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Ask OMS endpoints. All routes require authentication (enforced globally by
 * SecurityConfig's `anyRequest().authenticated()`); each answer is additionally
 * scoped and RBAC-checked inside [AiOrchestrationService].
 */
@RestController
@RequestMapping("/ai")
class AiController(
    private val orchestration: AiOrchestrationService,
) {

    @PostMapping("/chat")
    fun chat(@RequestBody request: AiChatRequest): ApiResponse<AiChatResponse> =
        ApiResponse.ok(orchestration.chat(request.query.orEmpty()))

    @PostMapping("/rephrase")
    fun rephrase(@RequestBody request: AiRephraseRequest): ApiResponse<AiRephraseResponse> =
        ApiResponse.ok(AiRephraseResponse(orchestration.rephrase(request)))

    @GetMapping("/suggestions")
    fun suggestions(): ApiResponse<List<String>> =
        ApiResponse.ok(orchestration.suggestions())
}
