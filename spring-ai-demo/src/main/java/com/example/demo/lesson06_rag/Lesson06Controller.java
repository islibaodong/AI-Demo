package com.example.demo.lesson06_rag;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 6 课：RAG（检索增强生成）Controller —— 让模型基于你自己的资料回答。
 *
 * <p>RAG 的答题流程（本课的 <code>/lesson6/ask</code> 就是照着标准三步做的）：</p>
 * <ol>
 *   <li><b>Retrieve 检索</b>：把用户问题向量化，到向量库 <code>similaritySearch</code> 取最相关的 topK 段。</li>
 *   <li><b>Augment 增强</b>：把这 topK 段拼进系统提示词，让模型"先看资料再回答"。</li>
 *   <li><b>Generate 生成</b>：约束模型只能依据资料作答，资料不足就明说不知道，抑制幻觉。</li>
 * </ol>
 *
 * <p>这里故意不封装成格式化摘要、引用来源等高级能力，而是把每一步都写在明处，方便你理解原理。
 * 官方更省事的方式是用 <code>QuestionAnswerAdvisor</code> 一把梭，理解本课后可自行替换体验。</p>
 *
 * <p><code>/lesson6/ingest</code> 负责先把知识库文档向量化灌进库（需要 API Key）。
 * 知识库内容见 <code>docs/spring-ai-knowledge.md</code>——里面编造的「创始人名字」是模型预训练里没有的，
 * 如果你能问答正确，就说明检索真的起作用了。</p>
 *
 * <p><b>与 LangChain 对照</b>：本接口 ≈ LangChain 的 <code>document loaders + VectorStore + Retriever + RetrievalQA</code>。</p>
 */
@RestController
public class Lesson06Controller {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final RagConfig ragConfig;

    public Lesson06Controller(ChatClient.Builder builder, VectorStore vectorStore, RagConfig ragConfig) {
        this.chatClient = builder.build();
        this.vectorStore = vectorStore;
        this.ragConfig = ragConfig;
    }

    /**
     * 把知识库文档向量化并灌入向量库（需 API Key）。可重复调用更新。
     */
    @GetMapping("/lesson6/ingest")
    public String ingest() throws java.io.IOException {
        List<Document> docs = ragConfig.loadKnowledgeDocuments();
        vectorStore.add(docs);                       // 逐段向量化并写入向量库
        return "已灌入 " + docs.size() + " 段文档到向量库。";
    }

    /**
     * 典型 RAG 问答：Retrieve -> Augment -> Generate。
     */
    @GetMapping("/lesson6/ask")
    public String ask(@RequestParam String q) {
        // 1) Retrieve：从向量库取与问题最相关的 topK 段
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(q)
                        .topK(3)                     // 取最相关的 3 段
                        .build());

        if (hits.isEmpty()) {
            return "（向量库为空或无相关文档。请先用 /lesson6/ingest 灌入知识库。）";
        }

        // 2) Augment：把检索到的段落拼成上下文
        String context = hits.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String system = """
                你是一位严谨的客服，请严格依据下面给出的资料回答问题。
                资料：%s

                要求：只根据资料作答；资料里没有的信息，明确说"资料中没有提到"，不要编造。
                """.formatted(context);

        // 3) Generate：让模型基于增强后的提示词作答
        return chatClient
                .prompt()
                .system(system)
                .user(q)
                .call()
                .content();
    }
}