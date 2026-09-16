package com.example.demo.lesson09_persistence;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 9 课：持久化 —— 会话记忆落进数据库，向量库落进文件。
 *
 * <p>第 4 课的记忆存在 <b>JVM 内存</b>里：应用一重启，"我的名字叫小明"就忘了。
 * 本课把它换成 <b>JDBC 数据库存储</b>，靠的是 Spring AI 的分层：</p>
 * <pre>
 *   ChatMemory（对话用）  =  MessageWindowChatMemory   ←-- 换这个不用动
 *        │  委托
 *   ChatMemoryRepository（存取用）  =  InMemory → JdbcChatMemoryRepository  ←-- 换的是这层
 * </pre>
 * <p>{@link JdbcChatMemoryRepository} 由 starter 自动配置（建表脚本也按数据库方言自动执行，
 * H2/PostgreSQL/MySQL/Oracle/SqlServer/Sqlite 都有对应 SQL），我们只负责给它一个数据源。</p>
 *
 * <p>验证持久化最直接的办法：<b>重启应用</b>后再访问 <code>/lesson9/memory/abc</code>，
 * 上一轮对话还在，就是持久化生效了。</p>
 *
 * <p>本类故意<b>不用</b>第 4 课的 {@code ChatMemory} Bean（那个是无库的内存版），
 * 而是各自 new 一个 —— 两个客户端可以并存，正好对照「同一套 API，只换存储层」。</p>
 *
 * <p><b>与 LangChain 对照</b>：JdbcChatMemoryRepository ≈ LangChain 的
 * <code>SQLChatMessageHistory</code>（PostgresChatMessageHistory 等）。</p>
 *
 * <p>试试：</p>
 * <ul>
 *   <li><code>curl -X POST "localhost:8080/lesson9/chat/abc" -d "我的名字叫小明"</code></li>
 *   <li><code>curl -X POST "localhost:8080/lesson9/chat/abc" -d "我叫什么名字？"</code></li>
 *   <li><code>curl "localhost:8080/lesson9/memory/abc"</code> —— 直接查看库里存的消息</li>
 *   <li>重启应用后再来一次上一步 —— 消息还在（第 4 课的内存版此时已清空）</li>
 * </ul>
 */
@RestController
public class Lesson09Controller {

    private static final String CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    private final ChatClient chatClient;
    private final JdbcChatMemoryRepository repository;

    public Lesson09Controller(ChatModel chatModel, JdbcChatMemoryRepository repository) {
        this.repository = repository;
        // 存储层换成 JDBC，对话层的 API 与第 4 课一字不差
        ChatMemory jdbcMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();

        // 这里用 ChatModel 直接开 Builder，与第 8 课同款写法（Builder 是 prototype 作用域，
        // 注入的也行，但直接 new 一眼看不出共享关系，更清晰）
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(jdbcMemory).build())
                .build();
    }

    /** 与第 4 课完全相同的使用方式，区别只在存储层换成了数据库表 */
    @PostMapping("/lesson9/chat/{sessionId}")
    public String chat(@PathVariable String sessionId, @RequestBody String userMessage) {
        return chatClient
                .prompt()
                .user(userMessage)
                .advisors(advisor -> advisor.param(CONVERSATION_ID_KEY, sessionId))
                .call()
                .content();
    }

    /** 绕过模型，直接查看数据库里为该会话存了哪些消息（重启后仍能读到 = 持久化生效） */
    @GetMapping("/lesson9/memory/{sessionId}")
    public List<Map<String, String>> memory(@PathVariable String sessionId) {
        return repository.findByConversationId(sessionId).stream()
                .map(m -> Map.of(
                        "type", m.getMessageType().name(),
                        "content", m.getText()))
                .toList();
    }

    /** 库里现存的全部会话 id */
    @GetMapping("/lesson9/conversations")
    public List<String> conversations() {
        return repository.findConversationIds();
    }
}
