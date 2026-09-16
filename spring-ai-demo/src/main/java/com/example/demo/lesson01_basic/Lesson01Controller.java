package com.example.demo.lesson01_basic;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 1 课：最简调用 —— 了解 Spring AI 的入口对象 {@link ChatClient}。
 *
 * <p>这是你第一次和 LLM 打交道。核心认识三点：</p>
 * <ol>
 *   <li><b>ChatClient</b>：所有对话的统一入口，链式（fluent）API 让它读起来像自然语言。</li>
 *   <li><b>prompt()</b>：开始构造一次请求；<b>user("...")</b> 填用户说的话。</li>
 *   <li><b>call().content()</b>：发送请求并取回模型返回的文本。</li>
 * </ol>
 *
 * <p><b>与 LangChain 对照</b>：这里约等于
 * <code>ChatOpenAI(model=...) + PromptTemplate + llm.invoke("...")</code>。
 * LangChain 以 Python 的 chain/runnable 为主，Spring AI 则以 ChatClient 的流式链为主。</p>
 *
 * <p>试试：<code>curl "http://localhost:8080/lesson1?q=用一句话介绍你自己"</code></p>
 */
@RestController
public class Lesson01Controller {

    // ChatClient.Builder 由 Spring AI 自动装配提供（spring-ai-starter-model-openai）。
    // 我们用它 build 出一个可用的 ChatClient，后面所有 lesson 都用同一个思路。
    private final ChatClient chatClient;

    public Lesson01Controller(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/lesson1")
    public String chat(@RequestParam(defaultValue = "用一句话介绍一下你自己") String q) {
        // chain 到 call().content()：拿到纯文本答复
        return chatClient
                .prompt()
                .user(q)
                .call()
                .content();
    }
}