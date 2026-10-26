package org.example.traveljavaserver.langchain;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 自定义 Redis 会话记忆存储（LangChain4j ChatMemoryStore 扩展点实现）
 * 说明：官方 langchain4j-redis 模块停留在 1.0.0-alpha1，与本项目 1.0.0-beta1 不对齐，
 *       故基于 Spring Data Redis 自行实现，直接复用 spring.data.redis 的连接配置（database=1）。
 *
 * 存储结构：Key = travel:chat:memory:{sessionId}，Value = 消息列表 JSON，TTL 7 天（每次更新续期）。
 */
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "travel:chat:memory:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redisTemplate.opsForValue().get(key(memoryId));
        return json == null ? List.of() : ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        redisTemplate.opsForValue().set(key(memoryId), ChatMessageSerializer.messagesToJson(messages), TTL);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(key(memoryId));
    }

    private String key(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }
}
