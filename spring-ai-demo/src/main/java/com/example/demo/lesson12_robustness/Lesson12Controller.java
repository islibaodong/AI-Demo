package com.example.demo.lesson12_robustness;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 12 课：健壮性 —— 超时、重试、熔断、降级（生产环境第一事故源）。
 *
 * <p><b>真实事故复盘</b>（选型对比文章里的案例）：应用没配超时，底层默认读超时 30 秒，
 * 而网关超时只有 5 秒——大模型推理稍慢，网关先超时重试，同一个请求被重复打到订单服务，
 * 最终数据库连接池被占满，整个服务雪崩。教训：<b>超时预算要像多米诺骨牌一样逐层收紧</b>：
 * 网关超时 > 应用层请求超时 > 连接超时，且每一层都要显式配置。</p>
 *
 * <p><b>Spring AI 2.0 的关键变化</b>：底层从 RestClient 换成了 OpenAI 官方 Java SDK
 * （openai-java，基于 OkHttp），超时与重试都内置于 SDK，配置项是：</p>
 * <pre>
 *   spring.ai.openai.timeout       # 请求总超时（java.time.Duration，默认 60s）
 *   spring.ai.openai.max-retries   # SDK 内置重试次数（默认 3），对 408/409/429/5xx 自动指数退避重试
 * </pre>
 * <p>注意 1.x 的 {@code spring.ai.retry.*}（Spring Retry 的 RetryTemplate）在 2.0
 * 已不参与 OpenAI 模型的重试——别再对着旧博客配置。</p>
 *
 * <p><b>事务陷阱</b>：绝不要在 {@code @Transactional} 方法里调 AI。AI 是延迟不可控的
 * 外部 I/O，事务会占住数据库连接几十秒，连接池必炸。正确姿势：先查数据（事务 A）
 * → 调 AI（无事务）→ 单独开一个小事务写结果（事务 B）。</p>
 *
 * <p><b>本课用假中转站的故障注入验证三种模式</b>（见 /tmp/fake_relay.py 的标记约定，
 * 换成真实模型时逻辑完全相同）：</p>
 * <ul>
 *   <li>RETRY-DEMO：前 2 次返回 429 → SDK 自动重试后成功</li>
 *   <li>FAIL-DEMO：永远 500 → 重试耗尽后触发降级/熔断</li>
 *   <li>SLOW-DEMO：睡 3 秒 → 触发读超时</li>
 * </ul>
 *
 * <p><b>与 LangChain 对照</b>：{@code max_retries} 参数（LangChain 的 ChatOpenAI 自带，
 * 默认 2 次）、{@code timeout} 参数、Tenacity 的 {@code @retry}（≈ Resilience4j 的
 * 重试+熔断）。概念完全同构，差别只在配置入口。</p>
 *
 * <p>试试：</p>
 * <ul>
 *   <li><code>curl "localhost:8080/lesson12/config"</code> —— 看当前生效的超时/重试配置</li>
 *   <li><code>curl "localhost:8080/lesson12/retry?q=hi"</code> —— 429 两次后靠重试成功</li>
 *   <li><code>curl "localhost:8080/lesson12/timeout"</code> —— 800ms 超时被触发</li>
 *   <li><code>curl "localhost:8080/lesson12/fallback?q=hi"</code> —— 主模型挂了自动降级</li>
 *   <li><code>curl "localhost:8080/lesson12/breaker"</code> ×3 —— 连续失败触发熔断，快速失败</li>
 * </ul>
 */
@RestController
public class Lesson12Controller {

    private final ChatClient primaryClient;
    private final Environment env;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    public Lesson12Controller(ChatModel chatModel, Environment env) {
        this.primaryClient = ChatClient.builder(chatModel).build();
        this.env = env;
    }

    /** 当前生效的超时/重试配置 + 生产检查清单（对应真实事故里的每一条） */
    @GetMapping("/lesson12/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("base-url", env.getProperty("spring.ai.openai.base-url"));
        m.put("timeout", env.getProperty("spring.ai.openai.timeout", "(默认 60s)"));
        m.put("max-retries", env.getProperty("spring.ai.openai.max-retries", "(默认 3)"));
        m.put("检查清单", Map.of(
                "超时预算", "网关超时 > 应用请求超时 > 连接超时，逐层收紧、全部显式配置",
                "重试", "只重试瞬态错误（429/5xx/超时）；业务 4xx、内容过滤错误绝不重试",
                "事务", "AI 调用永远不要包在 @Transactional 里",
                "降级", "关键路径准备备用模型；全部失败返回 503 而不是无限重试"));
        return m;
    }

    /**
     * 重试演示：假中转站对带 RETRY-DEMO 标记的请求前 2 次返回 429，
     * SDK 内置重试（max-retries，指数退避）自动扛过限流，最终成功。
     * 返回的文本里能看到这是第几次尝试成功。
     */
    @GetMapping("/lesson12/retry")
    public String retry(@RequestParam(defaultValue = "你好") String q) {
        return primaryClient.prompt()
                .user(q + "\n（RETRY-DEMO）")
                .call()
                .content();
    }

    /**
     * 超时演示：手工构建一个 800ms 就超时、不重试的 ChatModel，
     * 调用假中转站的 SLOW-DEMO（睡 3 秒），观察超时异常长什么样。
     * 生产上超时值要按"最慢可接受响应"来定，而不是默认值照抄。
     */
    @GetMapping("/lesson12/timeout")
    public String timeout() {
        OpenAiChatModel fastTimeoutModel = buildModel(Duration.ofMillis(800), 0, "gpt-4o");
        try {
            ChatClient.builder(fastTimeoutModel).build()
                    .prompt().user("随便说点什么（SLOW-DEMO）").call().content();
            return "竟然没超时？（检查假中转站是否在运行）";
        } catch (RuntimeException e) {
            return "✗ 请求在 800ms 超时被掐断。\n异常类型: " + e.getClass().getName()
                    + "\n信息: " + rootMessage(e);
        }
    }

    /**
     * 降级演示：主模型遇到持续故障（FAIL-DEMO 永远 500，重试耗尽后抛异常），
     * 捕获后切换到备用模型。生产上备用模型通常是另一家供应商（OpenAI → 国内模型），
     * 全部失败时返回 503 而不是无限重试。
     */
    @GetMapping("/lesson12/fallback")
    public String fallback(@RequestParam(defaultValue = "讲个笑话") String q) {
        return withFallback(
                () -> "[主模型回答] " + primaryClient.prompt()
                        .user(q + "\n（FAIL-DEMO）").call().content(),
                e -> {
                    // 降级：换备用模型。这里演示同一个中转站的备用模型，真实场景换另一家 baseUrl
                    OpenAiChatModel backupModel = buildModel(Duration.ofSeconds(60), 1, "gpt-4o-mini");
                    String backup = ChatClient.builder(backupModel).build()
                            .prompt().user(q).call().content();
                    return "⚠ 主模型失败（" + e.getClass().getSimpleName() + ": " + rootMessage(e)
                            + "），已降级到备用模型。\n[备用模型回答] " + backup;
                });
    }

    /**
     * 降级模板（静态方法以便离线单测）：主模型调用失败 → 执行 fallback。
     * 注意只 catch RuntimeException——降级不是吞异常，而是"换条路走"。
     */
    static String withFallback(Supplier<String> primary,
                               java.util.function.Function<RuntimeException, String> fallback) {
        try {
            return primary.get();
        } catch (RuntimeException e) {
            return fallback.apply(e);
        }
    }

    /** 手写熔断器（状态机见 {@link SimpleCircuitBreaker} 注释）。reset 参数可重建 */
    private SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(2, Duration.ofSeconds(5));

    /**
     * 熔断演示：底层模型持续 500（FAIL-DEMO）。前 2 次是"真失败"（重试已关掉，
     * 直接快速失败到熔断器），第 3 次起熔断打开、连网络都不碰。
     * reset=true 可重建熔断器，方便反复演示。
     */
    @GetMapping("/lesson12/breaker")
    public String breaker(@RequestParam(defaultValue = "false") boolean reset) {
        if (reset) {
            breaker = new SimpleCircuitBreaker(2, Duration.ofSeconds(5));
            return "熔断器已重置为 CLOSED";
        }
        OpenAiChatModel noRetryModel = buildModel(Duration.ofSeconds(60), 0, "gpt-4o");
        ChatClient client = ChatClient.builder(noRetryModel).build();
        Supplier<String> call = () -> client.prompt().user("hi（FAIL-DEMO）").call().content();

        String before = breaker.state().name();
        try {
            String answer = breaker.call(call);
            return "状态 " + before + " → CLOSED（调用成功）: " + answer;
        } catch (SimpleCircuitBreaker.CircuitOpenException e) {
            return "状态 " + before + " → OPEN：⚡ " + e.getMessage()
                    + "（对比 /lesson12/fallback：没有熔断时要白白等满重试+退避）";
        } catch (RuntimeException e) {
            return "状态 " + before + " → " + breaker.state().name()
                    + "：模型真实失败 " + e.getClass().getSimpleName()
                    + "（连续失败计数 +1，达到阈值后熔断）";
        }
    }

    /**
     * 手工组装一个 OpenAI ChatModel（2.0 的完整四件套）：
     * OkHttp httpClient → ClientOptions(baseUrl/apiKey/timeout/maxRetries)
     * → OpenAIClientImpl → OpenAiChatModel.builder().openAiClient(...)
     * 降级/超时演示里的"另一个模型"就是这么造出来的。
     */
    private OpenAiChatModel buildModel(Duration timeout, int maxRetries, String model) {
        SpringAiOpenAiHttpClient httpClient = SpringAiOpenAiHttpClient.builder()
                .timeout(timeout)
                .build();
        ClientOptions options = new ClientOptions.Builder()
                .httpClient(httpClient)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .build();
        OpenAIClient client = new OpenAIClientImpl(options);
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .options(OpenAiChatOptions.builder().model(model).build())
                .build();
    }

    /** 异常链可能包了好几层（OkHttp → SDK → Spring AI），取最里层的人话信息 */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg != null && msg.length() > 200 ? msg.substring(0, 200) + "…" : String.valueOf(msg);
    }
}
