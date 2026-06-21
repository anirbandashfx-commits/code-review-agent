package com.agentaudit.codereview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * ValkeyCacheService wraps Spring Data Redis for use as a Valkey-compatible
 * LLM response cache and session store.
 *
 * Valkey is an open-source Redis fork — the Spring Data Redis client
 * is fully compatible, so no driver changes are needed.
 *
 * AUDIT FLAW #4 — Caching disabled:
 *   get() and set() work correctly here, but app.cache.ttl-seconds=0
 *   in application.properties means every set() call stores with no TTL,
 *   and in LiteLLMService this class is never called at all.
 *
 *   Impact: every identical code review prompt hits the LLM API again,
 *   burning tokens and adding 500–2000ms latency unnecessarily.
 *
 *   Fix: set ttl-seconds to 3600 (1 hour) and call get() before every
 *   LLM call in LiteLLMService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValkeyCacheService {

    private final StringRedisTemplate redisTemplate;

    @Value("${app.cache.ttl-seconds:0}")
    private long ttlSeconds;

    /**
     * Retrieves a cached LLM response by cache key.
     * Returns empty if not found or if Valkey is unavailable.
     */
    public Optional<String> get(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                log.info("Cache HIT for key: {}", key);
                return Optional.of(value);
            }
            log.debug("Cache MISS for key: {}", key);
            return Optional.empty();
        } catch (Exception e) {
            // Valkey unavailable — degrade gracefully, don't crash the agent
            log.warn("Valkey unavailable on GET ({}): {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores an LLM response in Valkey with the configured TTL.
     *
     * AUDIT FLAW #4: if ttlSeconds == 0, this stores without expiry,
     * which means the cache fills up indefinitely. The real flaw is
     * that LiteLLMService never calls this method at all.
     */
    public void set(String key, String value) {
        try {
            if (ttlSeconds > 0) {
                redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
                log.debug("Cache SET key={} ttl={}s", key, ttlSeconds);
            } else {
                // TTL=0 means no expiry — a slow memory leak in production
                redisTemplate.opsForValue().set(key, value);
                log.warn("Cache SET without TTL — key={} will never expire", key);
            }
        } catch (Exception e) {
            // Cache write failure should never crash the agent
            log.warn("Valkey unavailable on SET ({}): {}", key, e.getMessage());
        }
    }

    /**
     * Stores agent session state so a run can be resumed after a crash.
     * Used by AgentGraphService to checkpoint between nodes.
     */
    public void saveSessionState(String sessionId, String stateJson) {
        set("session:" + sessionId, stateJson);
    }

    public Optional<String> loadSessionState(String sessionId) {
        return get("session:" + sessionId);
    }

    public void deleteSession(String sessionId) {
        try {
            redisTemplate.delete("session:" + sessionId);
        } catch (Exception e) {
            log.warn("Valkey delete failed for session {}: {}", sessionId, e.getMessage());
        }
    }
}
