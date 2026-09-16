package com.example.demo.lesson06_rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

/**
 * 第 6 课：RAG（检索增强生成）的基础设施配置。
 *
 * <p>两步成链：</p>
 * <ol>
 *   <li>{@link #vectorStore(EmbeddingModel)}：建一个<b>内存向量库</b> {@link SimpleVectorStore}。
 *       它用注入的 {@link EmbeddingModel}（application.yml 里配的 text-embedding-3-small）
 *       把每个文档变成向量存起来，供相似度检索。无需外部数据库，最适合学习。</li>
 *   <li>{@link #loadKnowledgeDocuments()}：切块工具——把一篇长文档按空行切成若干小段 <code>Document</code>，
 *       每一段对应一个向量。切块粒度直接影响检索效果。</li>
 * </ol>
 *
 * <p>向量库的「灌入」不是启动时自动做，而是由 {@link Lesson06Controller} 里的接口按需触发，
 * 避免没有 API Key 时应用启动失败——这正是教学演示的务实取舍。</p>
 *
 * <p><b>与 LangChain 对照</b>：SimpleVectorStore ≈ LangChain 的 <code>FAISS / Chroma</code>；
 * 切块（charSplit）≈ <code>RecursiveCharacterTextSplitter</code>；
 * EmbeddingModel ≈ <code>OpenAIEmbeddings</code>。</p>
 */
@Configuration
public class RagConfig {

    /**
     * 向量库文件的落盘位置：工程目录下 <code>data/vector-store.json</code>（已 gitignore）。
     * SimpleVectorStore 的 save/load 就是普通 JSON 文件——重启后无需重新调 Embedding API 灌库。
     */
    static final Path VECTOR_STORE_FILE = Path.of("data", "vector-store.json");

    /**
     * 内存向量库 Bean（lesson09 起带文件持久化）：
     * 启动时若存在上次灌库落盘的 JSON 文件就直接加载，否则从空库开始。
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        if (Files.exists(VECTOR_STORE_FILE)) {
            store.load(VECTOR_STORE_FILE.toFile());   // load/save 均不抛受检异常
        }
        return store;
    }

    /**
     * 把当前向量库写到磁盘。第 6 课的 ingest 端点在灌库后调用它，
     * 这样重启应用后向量库内容还在（不用再花一遍 Embedding 调用的钱）。
     */
    public void saveVectorStore(VectorStore vectorStore) {
        try {
            Files.createDirectories(VECTOR_STORE_FILE.getParent());
            ((SimpleVectorStore) vectorStore).save(VECTOR_STORE_FILE.toFile());
        } catch (IOException e) {
            // 持久化失败不影响本次运行，下次 ingest 会再写
        }
    }

    /**
     * 读取 classpath 下的知识库文档并切成 <code>Document</code> 列表。
     * 这里用最朴素的办法：按空行分段。真实项目会用更聪明的分块器。
     */
    public List<Document> loadKnowledgeDocuments() throws IOException {
        String raw = new ClassPathResource("docs/spring-ai-knowledge.md")
                .getContentAsString(StandardCharsets.UTF_8);

        List<Document> docs = new ArrayList<>();
        Arrays.stream(raw.split("\\n{2,}"))                 // 连续空行作为段落分隔
                .map(String::trim)
                .filter(StringUtils::hasText)               // 丢掉空段
                .map(paragraph -> new Document(paragraph))  // 每段 = 一个可检索的 Document
                .forEach(docs::add);
        return docs;
    }
}