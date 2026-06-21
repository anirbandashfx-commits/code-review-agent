package com.agentaudit.codereview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/**
 * LiteLLMService wraps all outbound LLM API calls.
 *
 * Architecture:
 *   Spring Boot → LiteLLMService → LiteLLM proxy → OpenRouter → Model
 *
 * AUDIT FLAWS in this class:
 *   FLAW #3 — No provider failover: if LiteLLM proxy is unreachable,
 *              the call fails immediately. There is no retry, no
 *              fallback to direct OpenRouter, and no circuit breaker.
 *
 *   FLAW #4 — No caching: every identical prompt results in a new
 *              LLM call. A Valkey cache check should happen before
 *              every call and a write-through after a successful call.
 *              This costs real money at scale.
 *
 *   FLAW #5 — Tool call inputs/outputs are not logged at a level
 *              that can be captured in production traces.
 */
@Slf4j
@Service
public class LiteLLMService {

    private final WebClient liteLLMClient;
    private final WebClient openRouterClient;
    private final ObjectMapper objectMapper;

    @Value("${litellm.model.primary}")
    private String primaryModel;

    @Value("${litellm.api-key}")
    private String liteLLMApiKey;

    @Value("${openrouter.api-key}")
    private String openRouterApiKey;

    public LiteLLMService(
            @Value("${litellm.base-url}") String liteLLMBaseUrl,
            @Value("${openrouter.base-url}") String openRouterBaseUrl,
            ObjectMapper objectMapper) {

        this.liteLLMClient = WebClient.builder()
                .baseUrl(liteLLMBaseUrl)
                .build();

        this.openRouterClient = WebClient.builder()
                .baseUrl(openRouterBaseUrl)
                .build();

        this.objectMapper = objectMapper;
    }

    /**
     * Sends a prompt to LiteLLM and returns the model's text response.
     *
     * @param systemPrompt  The system-level instruction for the model
     * @param userPrompt    The user-level input (code, question, etc.)
     * @param nodeLabel     Which graph node is calling — used in logs
     * @return              The model's text response, or null on failure
     *
     * AUDIT FLAW #3: returning null on failure instead of throwing a
     * typed exception means callers must null-check — and if they don't
     * (see AgentState.addIssue), the graph crashes silently.
     *
     * AUDIT FLAW #4: no cache lookup before calling the API.
     * A proper implementation would be:
     *   1. Hash (systemPrompt + userPrompt) → cache key
     *   2. Check Valkey: if hit, return cached response immediately
     *   3. On miss: call API, cache the result with TTL, return result
     */
    public String complete(String systemPrompt, String userPrompt, String nodeLabel) {

        // FLAW #5: inputs are logged at INFO not DEBUG, but in production
        // INFO is often suppressed and the tool call becomes invisible.
        // Should be: log.debug("[{}] LLM call — prompt tokens ~{}", ...)
        log.info("[{}] Calling LiteLLM model: {}", nodeLabel, primaryModel);

        // FLAW #4: cache check would go here
        // String cacheKey = DigestUtils.sha256Hex(systemPrompt + userPrompt);
        // String cached = valkeyCache.get(cacheKey);
        // if (cached != null) { log.info("Cache HIT for {}", nodeLabel); return cached; }

        try {
            ObjectNode requestBody = buildRequestBody(systemPrompt, userPrompt);
            String response = callLiteLLM(requestBody);

            // FLAW #4: cache write would go here
            // valkeyCache.setex(cacheKey, cacheTtlSeconds, response);

            // FLAW #5: response is not logged even at debug level
            log.info("[{}] LiteLLM call succeeded", nodeLabel);
            return response;

        } catch (WebClientResponseException e) {
            log.error("[{}] LiteLLM HTTP error: {} — {}", nodeLabel, e.getStatusCode(), e.getMessage());

            // FLAW #3: instead of failing here, we should try the fallback model
            // or retry with exponential backoff. Example fix:
            //   return callWithFallback(systemPrompt, userPrompt, nodeLabel);
            return null; // ← FLAW: null propagates silently into agent state

        } catch (Exception e) {
            log.error("[{}] LiteLLM unexpected error: {}", nodeLabel, e.getMessage());
            return null; // ← FLAW: same silent null propagation
        }
    }

    /**
     * Calls LiteLLM proxy synchronously (blocks up to 30s).
     */
    private String callLiteLLM(ObjectNode requestBody) {
        JsonNode response = liteLLMClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + liteLLMApiKey)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .block();

        return extractContent(response);
    }

    /**
     * Direct OpenRouter call — intended as a fallback when LiteLLM is down.
     *
     * AUDIT FLAW #3: this method exists but is NEVER called.
     * It should be invoked in the catch blocks of complete() above.
     */
    public String callOpenRouterDirectly(String systemPrompt, String userPrompt, String nodeLabel) {
        log.warn("[{}] Falling back to direct OpenRouter call", nodeLabel);
        try {
            ObjectNode requestBody = buildRequestBody(systemPrompt, userPrompt);
            // Override model for direct OpenRouter format
            requestBody.put("model", "anthropic/claude-haiku-4-5");

            JsonNode response = openRouterClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + openRouterApiKey)
                    .header("Content-Type", "application/json")
                    .header("HTTP-Referer", "https://agentaudit.io")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            return extractContent(response);
        } catch (Exception e) {
            log.error("[{}] OpenRouter fallback also failed: {}", nodeLabel, e.getMessage());
            return null;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    private ObjectNode buildRequestBody(String systemPrompt, String userPrompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", primaryModel);
        body.put("max_tokens", 1024);
        body.put("temperature", 0.2);

        ArrayNode messages = body.putArray("messages");

        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);

        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        return body;
    }

    private String extractContent(JsonNode response) {
        if (response == null) return null;
        try {
            return response
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();
        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", e.getMessage());
            return null;
        }
    }
}
