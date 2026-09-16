package com.example.demo.lesson03_stream;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

/**
 * 第 3 课：流式输出（Streaming / SSE）。
 *
 * <p>前面用 <code>call()</code> 是「等全部生成完再一次返回」，适合短回答、结构化任务。
 * 但聊天、实时补全需要「边生成边返回」——这就是本课：把 <code>call()</code> 换成
 * <code>stream()</code>，返回 <b>Reactive 的 {@link Flux}&lt;String&gt;</b>，每个元素是一段增量文本。</p>
 *
 * <p>Spring 会把它渲染成 SSE（Server-Sent Events，<code>text/event-stream</code>），
 * 浏览器或 <code>curl</code> 能逐 token 收到内容，体验「打字机效果」。</p>
 *
 * <p><b>与 LangChain 对照</b>：<code>stream().content()</code> ≈ LangChain 的
 * <code>runnable.stream()（streaming=True）</code>，都会产出一个个增量块（token chunk）。</p>
 *
 * <p>试试：<code>curl -N "http://localhost:8080/lesson3/stream?topic=Java"</code>，
 * 加 -N 表示不缓冲、立即显示。</p>
 */
@RestController
public class Lesson03Controller {

    private final ChatClient chatClient;

    public Lesson03Controller(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping(value = "/lesson3/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam(defaultValue = "Spring Boot") String topic) {
        // 关键差异就在 stream() 而不是 call()：立即返回 Flux，逐 token 推送
        return chatClient
                .prompt()
                .user("请用一句话通俗地介绍：" + topic)
                .stream()
                .content();
    }
}