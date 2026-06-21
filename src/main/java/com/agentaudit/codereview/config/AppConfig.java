package com.agentaudit.codereview.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Application configuration beans.
 */
@Configuration
public class AppConfig {

    /**
     * ObjectMapper configured for Java 8 date/time types (Instant, etc.)
     * Used for serialising AgentState to/from Valkey.
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    /**
     * StringRedisTemplate — works with both Redis and Valkey
     * (Valkey is a Redis-compatible fork; the Spring Data Redis
     * client connects to it without any changes).
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
