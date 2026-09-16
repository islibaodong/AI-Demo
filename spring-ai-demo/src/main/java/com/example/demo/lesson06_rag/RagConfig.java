package com.example.demo.lesson06_rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
     * 内存向量库 Bean。只要项目用了它，框架才会真正初始化向量功能。
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
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