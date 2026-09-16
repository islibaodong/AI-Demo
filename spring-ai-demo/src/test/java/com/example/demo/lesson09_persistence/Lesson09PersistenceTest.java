package com.example.demo.lesson09_persistence;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 9 课单元测试：全程不联网、不依赖外部服务。
 * <ol>
 *   <li>JDBC 记忆持久化：真 JDBC + H2 内存库（建表后 saveAll → findByConversationId 往返），
 *       顺带验证 MessageWindowChatMemory 挂上 JDBC 存储后窗口裁剪依然生效。</li>
 *   <li>SimpleVectorStore 的文件持久化：add → save → 新实例 load → 检索命中。</li>
 * </ol>
 */
class Lesson09PersistenceTest {

    // ---------- JDBC 会话记忆 ----------

    /**
     * 建一个挂在 H2 内存库上的 JdbcChatMemoryRepository（建表 SQL 与 jar 内
     * schema-h2.sql 一致，由自动配置在真实应用里执行）。
     */
    private JdbcChatMemoryRepository h2Repository() throws Exception {
        var dataSource = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:lesson09_%d;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()), "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
                    conversation_id VARCHAR(36) NOT NULL,
                    content LONGVARCHAR NOT NULL,
                    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
                    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    sequence_id BIGINT NOT NULL
                )""");
        return JdbcChatMemoryRepository.builder().jdbcTemplate(jdbc).build();
    }

    @Test
    void messagesSurviveRepositoryRoundTrip() throws Exception {
        JdbcChatMemoryRepository repository = h2Repository();

        repository.saveAll("abc", List.of(new UserMessage("我的名字叫小明"),
                new AssistantMessage("你好，小明！")));

        // 模拟"应用重启"：同一个库，重新读出
        List<org.springframework.ai.chat.messages.Message> stored =
                repository.findByConversationId("abc");

        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getText()).isEqualTo("我的名字叫小明");
        assertThat(stored.get(0).getMessageType().name()).isEqualTo("USER");
        assertThat(stored.get(1).getText()).isEqualTo("你好，小明！");
        assertThat(repository.findConversationIds()).containsExactly("abc");
    }

    @Test
    void windowedMemoryTrimsOldMessagesOnTopOfJdbc() throws Exception {
        JdbcChatMemoryRepository repository = h2Repository();
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(2)   // 只留最近 2 条
                .build();

        memory.add("s1", new UserMessage("第 1 句"));
        memory.add("s1", new UserMessage("第 2 句"));
        memory.add("s1", new UserMessage("第 3 句"));   // 挤掉第 1 句

        List<org.springframework.ai.chat.messages.Message> stored =
                repository.findByConversationId("s1");
        assertThat(stored).extracting(org.springframework.ai.chat.messages.Message::getText)
                .containsExactly("第 2 句", "第 3 句");
    }

    // ---------- 向量库文件持久化 ----------

    /** 返回固定向量的假 EmbeddingModel：向量化 0 次网络调用，测试可离线跑 */
    private EmbeddingModel fakeEmbedding() {
        EmbeddingModel em = Mockito.mock(EmbeddingModel.class);
        Mockito.when(em.embed(Mockito.anyString())).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        Mockito.when(em.embed(Mockito.any(Document.class))).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        Mockito.when(em.embedForResponse(Mockito.anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return new EmbeddingResponse(texts.stream()
                    .map(t -> new Embedding(new float[] {0.1f, 0.2f, 0.3f}, t.hashCode()))
                    .toList());
        });
        return em;
    }

    @Test
    void vectorStoreRoundTripsThroughFile(@TempDir Path tmp) {
        EmbeddingModel em = fakeEmbedding();

        SimpleVectorStore first = SimpleVectorStore.builder(em).build();
        first.add(List.of(new Document("Spring AI 是 Spring 官方的 AI 框架。")));
        first.save(tmp.resolve("vs.json").toFile());

        // 模拟重启：全新的空库，从文件加载
        SimpleVectorStore second = SimpleVectorStore.builder(em).build();
        second.load(tmp.resolve("vs.json").toFile());

        List<Document> hits = second.similaritySearch(
                SearchRequest.builder().query("Spring AI").topK(3).build());
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getText()).contains("Spring 官方的 AI 框架");
    }
}
