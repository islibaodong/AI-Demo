package com.example.demo.lesson14_observability;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.observation.ModelUsageMetricsGenerator;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 14 课：可观测与成本 —— 让"每次调用花了多少 token、多少钱、多长时间"变成可查询的数字。
 *
 * <p><b>三层观测体系</b>（从下往上）：</p>
 * <ol>
 *   <li><b>原始用量</b> {@code /lesson14/usage}：每次响应的元数据里都带着
 *       {@link Usage}（prompt/completion/total 三项），这是所有成本核算的数据源。</li>
 *   <li><b>指标聚合</b> {@code /lesson14/metrics}：Micrometer 把逐次用量聚合成
 *       计数器/计时器。Spring AI 内置了 {@link ModelUsageMetricsGenerator}，按
 *       GenAI 语义约定注册 <code>gen_ai.client.token.usage</code> 计数器
 *       （tag {@code gen_ai.token.type} = input / output / total）——本课手动调用它，
 *       并补上业务指标（成本累计、请求耗时）。生产环境由 actuator 自动接线，
 *       再用 Prometheus/Micrometer Tracing 导出到监控系统。</li>
 *   <li><b>预算硬闸</b> {@code /lesson14/budget}：{@link TokenBudgetAdvisor}
 *       累计用量，超限短路——观测发现问题，预算阻止问题继续烧钱。</li>
 * </ol>
 *
 * <p>另有 <code>/lesson14/cap</code>：单次输出的硬上限（maxTokens 选项）——
 * completion 单价是 prompt 的 3~4 倍，给输出封顶是最直接的省钱手段。</p>
 *
 * <p><b>与 LangChain 对照</b>：LangChain 靠 callback 统计用量（如 get_openai_callback），
 * Tracing 靠 LangSmith；Spring AI 的等价物是 Micrometer Observation（内建、无需换库），
 * 导出端随便选 Prometheus/OTLP/Datadog。</p>
 *
 * <p>试试：</p>
 * <ul>
 *   <li><code>curl "localhost:8080/lesson14/usage?q=一句话介绍 Spring AI"</code> —— 真实用量 + 成本估算</li>
 *   <li><code>curl "localhost:8080/lesson14/metrics?q=你好"</code> —— 累计指标快照</li>
 *   <li><code>curl "localhost:8080/lesson14/budget?q=你好"</code> —— 反复调用直到预算耗尽被短路</li>
 *   <li><code>curl "localhost:8080/lesson14/cap?maxTokens=8"</code> —— completion 被截断到上限内</li>
 * </ul>
 */
@RestController
public class Lesson14Controller {

    /** 预算上限（教学值）：假中转站每次调用约几十 token，调几次就能触发短路 */
    static final int DEMO_TOKEN_LIMIT = 100;

    private final ChatModel chatModel;

    private final ChatClient client;

    /**
     * 本课自建 registry 以便直接"看见数字"。
     * 生产环境：加 spring-boot-starter-actuator 后 Boot 自动装配全局 MeterRegistry
     * （注入即可），配合 management.endpoints.web.exposure.include=metrics/prometheus 导出。
     */
    private final MeterRegistry registry = new SimpleMeterRegistry();

    /** 内置指标名：GenAI 社区语义约定，Spring AI 2.0 内置生成器注册的就是它 */
    static final String TOKEN_USAGE_METRIC = "gen_ai.client.token.usage";
    static final String TOKEN_TYPE_TAG = "gen_ai.token.type";
    /** 本课自加的业务指标：钱与耗时（内置指标只管 token，不管钱） */
    static final String COST_METRIC = "lesson14.cost.usd";
    static final String LATENCY_METRIC = "lesson14.request.duration";

    private final TokenBudgetAdvisor budgetAdvisor = new TokenBudgetAdvisor(DEMO_TOKEN_LIMIT);

    public Lesson14Controller(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.client = ChatClient.builder(chatModel).build();
    }

    // ---------- 1) 原始用量 + 成本估算 ----------

    /**
     * 真实调一次，把响应元数据里的 {@link Usage} 拆出来，
     * 再用 {@link ModelPricing} 换算成钱。返回的每个数字都来自这一次真实调用。
     */
    @GetMapping("/lesson14/usage")
    public Map<String, Object> usage(@RequestParam(defaultValue = "用一句话介绍 Spring AI") String q) {
        long start = System.currentTimeMillis();
        ChatResponse response = client.prompt().user(q).call().chatResponse();
        long latencyMs = System.currentTimeMillis() - start;

        Usage usage = usageOf(response);
        String model = modelOf(response);
        ModelPricing pricing = ModelPricing.of(model);
        ModelPricing.Cost cost = pricing.estimate(usage);

        recordMetrics(model, usage, cost, latencyMs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model);
        result.put("promptTokens", usage.getPromptTokens());
        result.put("completionTokens", usage.getCompletionTokens());
        result.put("totalTokens", usage.getTotalTokens());
        result.put("promptCostUsd", cost.promptCost());
        result.put("completionCostUsd", cost.completionCost());
        result.put("totalCostUsd", cost.totalCost());
        result.put("latencyMs", latencyMs);
        result.put("note", "用量来自响应元数据；成本 = tokens × 单价 ÷ 1M（completion 单价通常是 prompt 的数倍）");
        return result;
    }

    // ---------- 2) 指标聚合快照 ----------

    /**
     * 调用一次（计入指标），然后返回整个 registry 的快照——相当于一个迷你
     * /actuator/metrics。重点看两类：内置的 token 用量计数器（input/output/total 三条）
     * 和自加的成本/耗时指标。
     */
    @GetMapping("/lesson14/metrics")
    public Map<String, Object> metrics(@RequestParam(defaultValue = "你好") String q) {
        long start = System.currentTimeMillis();
        ChatResponse response = client.prompt().user(q).call().chatResponse();
        long latencyMs = System.currentTimeMillis() - start;

        Usage usage = usageOf(response);
        String model = modelOf(response);
        ModelPricing.Cost cost = ModelPricing.of(model).estimate(usage);
        recordMetrics(model, usage, cost, latencyMs);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("gen_ai.client.token.usage", Map.of(
                "input", counterValue(TOKEN_USAGE_METRIC, TOKEN_TYPE_TAG, "input"),
                "output", counterValue(TOKEN_USAGE_METRIC, TOKEN_TYPE_TAG, "output"),
                "total", counterValue(TOKEN_USAGE_METRIC, TOKEN_TYPE_TAG, "total")));
        snapshot.put("lesson14.cost.usd", totalCostAccumulated());
        snapshot.put("lesson14.request.duration",
                Map.of("count", requestCount(), "avgMs", averageLatencyMs()));
        snapshot.put("note", "生产环境由 actuator + Prometheus 采集这些同名指标并配告警");
        return snapshot;
    }

    // ---------- 3) 预算硬闸 ----------

    /**
     * 每次调用都往同一个 {@link TokenBudgetAdvisor} 里累计（上限 {@value #DEMO_TOKEN_LIMIT}）。
     * 反复 curl：前几次正常返回，某一次开始短路返回"预算防护"——此时请求根本没出网。
     */
    @GetMapping("/lesson14/budget")
    public Map<String, Object> budget(@RequestParam(defaultValue = "你好") String q) {
        // 预算版 client：挂在共享的 budgetAdvisor 上（预算池跨请求持续累计）
        ChatClient budgetClient = ChatClient.builder(chatModel)
                .defaultAdvisors(budgetAdvisor)
                .build();
        String reply = budgetClient.prompt().user(q).call().content();
        return Map.of(
                "reply", reply,
                "usedTokens", budgetAdvisor.usedTokens(),
                "remaining", budgetAdvisor.remaining(),
                "tokenLimit", DEMO_TOKEN_LIMIT);
    }

    // ---------- 4) 单次输出上限 ----------

    /**
     * 用 OpenAiChatOptions.maxTokens 给 completion 封顶（请求级 options，只影响本次）。
     * completion 单价是 prompt 的数倍——给输出封顶是性价比最高的省钱手段，
     * 比换便宜模型立竿见影。返回的 completionTokens ≤ 上限即证明生效。
     */
    @GetMapping("/lesson14/cap")
    public Map<String, Object> cap(@RequestParam(defaultValue = "用 100 字介绍 Spring AI") String q,
            @RequestParam(defaultValue = "16") int maxTokens) {
        ChatResponse response = client.prompt()
                .user(q)
                // 注意 2.0 的签名：options() 接收的是 Builder，不是 build() 之后的成品
                .options(OpenAiChatOptions.builder().maxTokens(maxTokens))
                .call().chatResponse();
        Usage usage = usageOf(response);
        return Map.of(
                "maxTokensRequested", maxTokens,
                "completionTokens", usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens(),
                "withinCap", usage.getCompletionTokens() == null
                        || usage.getCompletionTokens() <= maxTokens,
                "reply", response.getResult().getOutput().getText());
    }

    // ---------- 指标与工具方法 ----------

    /**
     * 记录一次调用的三类指标。
     * 内置指标由 Spring AI 自带的 {@link ModelUsageMetricsGenerator} 注册——
     * 生产环境里它在 Observation 链路中被自动调用，这里手动调用以便在没有
     * actuator 的教学环境也能看到同样的数字。
     */
    private void recordMetrics(String model, Usage usage, ModelPricing.Cost cost, long latencyMs) {
        ModelUsageMetricsGenerator.generate(usage, new Observation.Context(), registry);
        registry.counter(COST_METRIC, "model", model).increment(cost.totalCost().doubleValue());
        registry.timer(LATENCY_METRIC, "model", model).record(Duration.ofMillis(latencyMs));
    }

    /** 安全取用量：API 没返回时给 0，观测代码绝不能反过来把业务调用搞挂 */
    static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null
                || response.getMetadata().getUsage() == null) {
            return new org.springframework.ai.chat.metadata.DefaultUsage(0, 0, 0);
        }
        return response.getMetadata().getUsage();
    }

    /** 安全取模型名：未知时按 unknown 处理 */
    static String modelOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null
                || response.getMetadata().getModel() == null) {
            return "unknown";
        }
        return response.getMetadata().getModel();
    }

    /** null 安全的计数器读取：指标不存在（还没记录过）返回 0 而不是抛异常 */
    private double counterValue(String name, String tagKey, String tagValue) {
        Counter counter = registry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** 所有模型的成本累计（跨 model tag 求和） */
    private double totalCostAccumulated() {
        return registry.find(COST_METRIC).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private long requestCount() {
        Timer timer = registry.find(LATENCY_METRIC).timer();
        return timer == null ? 0 : timer.count();
    }

    private double averageLatencyMs() {
        Timer timer = registry.find(LATENCY_METRIC).timer();
        if (timer == null || timer.count() == 0) {
            return 0.0;
        }
        return timer.mean(TimeUnit.MILLISECONDS);
    }
}
