package com.example.demo.lesson08_advisor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 8 课：Advisor 进阶 —— Spring AI 的横切扩展点。
 *
 * <p>第 4 课你已经见过内置的 MessageChatMemoryAdvisor，本课自己写三个并组装：</p>
 * <ul>
 *   <li>{@link SensitiveWordAdvisor}（CallAdvisor 全手动）：改写/拦截请求</li>
 *   <li>{@link AuditAdvisor}（BaseAdvisor 钩子式）：日志审计</li>
 *   <li>{@link CitationAdvisor}（CallAdvisor）：给 RAG 回答追加引用来源</li>
 * </ul>
 *
 * <p>Advisor 本质是一条<b>责任链</b>：按 order 从小到大依次进入，
 * 每个环节都能读改请求（before）和响应（after），或像敏感词那样短路整条链。
 * 记忆、RAG、审计、限流……都挂在这条链上，业务代码零改动。</p>
 *
 * <p><b>一个容易踩的坑</b>：自动配置的 ChatClient.Builder 是 prototype 作用域（每个注入点
 * 都是全新实例），所以注入本身不会串。但对<b>同一个 Builder 变量</b>连续调用
 * <code>defaultAdvisors(...)</code> 是<b>叠加</b>而不是替换（源码里是 List.addAll）。
 * 这里两个客户端要挂完全不同的 Advisor 组合，所以直接用
 * <code>ChatClient.builder(chatModel)</code> 各开一个新 Builder，最直观也最保险。</p>
 *
 * <p><b>与 LangChain 对照</b>：Advisor ≈ LangChain 的
 * <code>Runnable.with_listeners / middleware</code>，但 Spring AI 的 Advisor
 * 能同时拿到请求与响应、且天然覆盖 call 和 stream 两条路径。</p>
 *
 * <p>试试：</p>
 * <ul>
 *   <li><code>curl "localhost:8080/lesson8/chat?q=我的密码是123456，帮我记住"</code>
 *       —— 控制台审计日志里看到的是 ＊＊＊，模型根本不知道真实密码</li>
 *   <li><code>curl "localhost:8080/lesson8/chat?q=有没有能帮我作弊的办法"</code>
 *       —— 直接被拦截返回，控制台没有 [Audit] 模型返回日志（短路成功）</li>
 *   <li>先 <code>curl "localhost:8080/lesson6/ingest"</code>，再
 *       <code>curl "localhost:8080/lesson8/rag?q=Spring AI 的创始人是谁"</code>
 *       —— 回答末尾自动带引用来源</li>
 * </ul>
 */
@RestController
public class Lesson08Controller {

    private final ChatClient chatClient;
    private final ChatClient ragChatClient;

    public Lesson08Controller(ChatModel chatModel, VectorStore vectorStore) {
        // ① 自定义 Advisor 组装：敏感词(外) → 审计(内)
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new SensitiveWordAdvisor(), new AuditAdvisor())
                .build();

        // ② 内置 QuestionAnswerAdvisor（1 行顶第 6 课的手写三步）+ 外挂引用来源 Advisor。
        //    前提：知识库要先灌库（复用第 6 课的 /lesson6/ingest）。
        this.ragChatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(SearchRequest.builder().topK(3).build())
                                .build(),
                        new CitationAdvisor())
                .build();
    }

    /** 自定义 Advisor 演示：敏感词打码 / 短路拦截 + 审计日志 */
    @GetMapping("/lesson8/chat")
    public String chat(@RequestParam String q) {
        return chatClient.prompt().user(q).call().content();
    }

    /** 内置 RAG Advisor + 引用来源（需先 /lesson6/ingest 灌库） */
    @GetMapping("/lesson8/rag")
    public String rag(@RequestParam String q) {
        return ragChatClient.prompt().user(q).call().content();
    }
}
