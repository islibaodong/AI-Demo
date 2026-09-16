package com.example.demo.lesson05_functions;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 5 课：函数调用（Function Calling / Tools）Controller。
 *
 * <p>通过 <code>builder.defaultTools(appTools)</code> 把 {@link AppTools} 上的所有 @Tool 方法
 * 暴露给模型。之后用户问「北京天气」「算 3*9+5」「现在几点」，模型会自动挑选合适的工具去执行。</p>
 *
 * <p>课时打开 DEBUG 日志（application.yml 里配置了
 * <code>logging.level.org.springframework.ai=DEBUG</code>），
 * 能在控制台看到「模型『请求调用 -> 框架执行 -> 结果回喂』」的完整过程，非常有教育意义。</p>
 *
 * <p>试试：
 * <code>curl "localhost:8080/lesson5?q=北京今天天气如何"</code><br>
 * <code>curl "localhost:8080/lesson5?q=帮我算一下 (12+8)*4 等于几"</code><br>
 * <code>curl "localhost:8080/lesson5?q=现在几点"</code></p>
 */
@RestController
public class Lesson05Controller {

    private final ChatClient chatClient;

    public Lesson05Controller(ChatModel chatModel, AppTools appTools) {
        // 自动配置的 ChatClient.Builder 是 prototype 作用域——每个注入点拿到的都是全新 Builder，
        // 所以本课直接注入它没有问题。但要注意：对同一个 Builder 连续 defaultTools/defaultAdvisors
        // 是「叠加」而非替换（源码是 List.addAll），复用同一个 Builder 变量构建多个客户端时会互相串。
        this.chatClient = ChatClient.builder(chatModel)
                // 把工具对象传入，其上的 @Tool 方法即可被模型调用
                .defaultTools(appTools)
                .build();
    }

    @GetMapping("/lesson5")
    public String ask(@RequestParam(defaultValue = "北京今天天气如何") String q) {
        return chatClient
                .prompt()
                .user(q)
                .call()
                .content();
    }
}