package com.example.demo.lesson04_memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 第 4 课：会话记忆的配置。
 *
 * <p>{@link MessageWindowChatMemory} 是内置的「内存窗口」记忆实现：只保留最近 N 条消息，
 * 超出就丢掉最旧的，从而控制发送给模型的上下文长度（也省钱、省 token）。</p>
 *
 * <p>这里手动定义 {@link ChatMemory} Bean，而不是依赖自动配置，
 * 好处是可以直观地看到记忆存在哪里、窗口滑动的行为由什么决定。生产环境一般换成 Redis 等外部实现。</p>
 *
 * <p><b>与 LangChain 对照</b>：ChatMemory ≈ LangChain 的 <code>ChatMessageHistory</code>
 * （配 <code>RunnableWithMessageHistory</code>），MessageWindowChatMemory ≈ <code>WindowBufferWindowMemory</code>。</p>
 */
@Configuration
public class MemoryConfig {

    @Bean
    public ChatMemory chatMemory() {
        // 每个会话最多记住最近 10 条用户/助手消息，再旧的自动淘汰
        return MessageWindowChatMemory.builder()
                .maxMessages(10)
                .build();
    }
}