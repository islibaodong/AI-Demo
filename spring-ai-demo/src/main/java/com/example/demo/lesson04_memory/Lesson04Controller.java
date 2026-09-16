package com.example.demo.lesson04_memory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 4 课：会话记忆（有状态的多轮对话）。
 *
 * <p>上一课的 LLM 默认是「无状态」的——每次请求都当第一次。要让机器人「记得上句」，需要：</p>
 * <ol>
 *   <li>一个 {@link ChatMemory} 存历史消息（见 {@link MemoryConfig}）。</li>
 *   <li>用一个 <b>Advisor（增强器）</b>{@link MessageChatMemoryAdvisor} 挂在 ChatClient 上，
 *       每次请求前自动把该会话的历史拼进 prompt，请求后再把本次问答存回去。</li>
 *   <li>通过 <code>advisors(spec -&gt; spec.param(会话id))</code> 指定「这是哪个会话」，
 *       不同会话彼此隔离互不串台。</li>
 * </ol>
 *
 * <p><b>Advisor 是 Spring AI 的核心机制</b>：它是请求/响应的拦截器，可做记忆、RAG、安全审查等横切增强。</p>
 *
 * <p><b>与 LangChain 对照</b>：MessageChatMemoryAdvisor ≈ LangChain 的
 * <code>RunnableWithMessageHistory</code>；Advisor ≈ <code>middleware / callbacks</code> 的横切概念。</p>
 *
 * <p>试试（同一 sessionId 多轮）：
 * <code>curl -X POST "localhost:8080/lesson4/chat/abc" -d "我的名字叫小明"</code><br>
 * <code>curl -X POST "localhost:8080/lesson4/chat/abc" -d "我叫什么名字？"</code></p>
 */
@RestController
public class Lesson04Controller {

    private static final String CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    private final ChatClient chatClient;

    public Lesson04Controller(ChatClient.Builder builder, ChatMemory chatMemory) {
        // 把记忆增强器设成默认 Advisor，所有请求自动带上记忆
        this.chatClient = builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @PostMapping("/lesson4/chat/{sessionId}")
    public String chat(@PathVariable String sessionId, @RequestBody String userMessage) {
        return chatClient
                .prompt()
                .user(userMessage)
                // 指定该请求所属的会话 id：多个用户/会话由此隔离
                .advisors(advisor -> advisor.param(CONVERSATION_ID_KEY, sessionId))
                .call()
                .content();
    }
}