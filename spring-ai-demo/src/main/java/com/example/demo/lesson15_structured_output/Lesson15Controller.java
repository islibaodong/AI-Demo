package com.example.demo.lesson15_structured_output;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 15 课：结构化输出修复 —— 把"模型输出坏了"从 500 错误变成可降级的普通分支。
 *
 * <p><b>为什么单开一课？</b>第 2 课的 {@code .entity()} 在理想世界里很好用，
 * 但生产环境模型会输出寒暄包裹、被 maxTokens 截断、字段类型写错的 JSON。
 * 直接崩掉是线上一等事故源（尤其这段 JSON 往往是给下游系统用的）。</p>
 *
 * <p><b>两个端点对照：</b></p>
 * <ul>
 *   <li>{@code /lesson15/naive}：<b>反面教材</b>——直接 {@code .entity()}。
 *       干净输出时一切正常；带上 CHATTY-JSON 标记让假中转站返回"寒暄包裹"
 *       的 JSON，当场 500。</li>
 *   <li>{@code /lesson15/repair}：{@link StructuredOutputRepairer} 三级管道
 *       （DIRECT → EXTRACT → MODEL_REPAIR），返回值里带 strategy / attempts /
 *       rawPreview——修复过程完全可观测。TRUNC-JSON 标记返回截断 JSON，
 *       本地抽取救不了，看它升级到模型修复救回来。</li>
 * </ul>
 *
 * <p><b>假中转站的故障标记</b>（埋在 q 里）：</p>
 * <ul>
 *   <li>CLEAN-JSON → 干净的合法 JSON（一切正常）</li>
 *   <li>CHATTY-JSON → 寒暄包裹的合法 JSON（本地抽取可救）</li>
 *   <li>TRUNC-JSON → 截断的 JSON（只能模型修复）</li>
 *   <li>FENCE-JSON → markdown 代码块包裹（2.0 已内置清理，naive 也不炸——
 *       这是 2.0 相对 1.x 的改进点）</li>
 * </ul>
 *
 * <p><b>与 LangChain 对照</b>：OutputFixingParser = 本课的 MODEL_REPAIR 一级；
 * Pydantic + Instructor 的重试验证是同一思路的 Python 生态形态。</p>
 *
 * <p>试试：</p>
 * <ul>
 *   <li><code>curl "localhost:8080/lesson15/naive?q=CLEAN-JSON"</code> —— 正常时 entity 没问题</li>
 *   <li><code>curl "localhost:8080/lesson15/naive?q=CHATTY-JSON"</code> —— 寒暄包裹，直接 500</li>
 *   <li><code>curl "localhost:8080/lesson15/repair?q=CHATTY-JSON"</code> —— strategy=EXTRACT 救回</li>
 *   <li><code>curl "localhost:8080/lesson15/repair?q=TRUNC-JSON"</code> —— strategy=MODEL_REPAIR 救回</li>
 *   <li><code>curl "localhost:8080/lesson15/repair?q=FENCE-JSON"</code> —— strategy=DIRECT（2.0 内置清理）</li>
 * </ul>
 */
@RestController
public class Lesson15Controller {

    private final ChatClient client;

    private final StructuredOutputRepairer repairer = new StructuredOutputRepairer();

    public Lesson15Controller(ChatModel chatModel) {
        this.client = ChatClient.builder(chatModel).build();
    }

    // ---------- 1) 反面教材：裸 entity，坏输出直接 500 ----------

    /**
     * 裸 {@code .entity()}：输出坏 → BeanOutputConverter 抛异常 → 接口 500。
     * q 里的标记决定假中转站返回什么：CLEAN-JSON（默认，正常）/ CHATTY-JSON（炸）/
     * FENCE-JSON（2.0 内置清理，不炸）。
     */
    @GetMapping("/lesson15/naive")
    public OrderInfo naive(@RequestParam(defaultValue = "CLEAN-JSON") String q) {
        return client.prompt().user(q).call().entity(OrderInfo.class);
    }

    // ---------- 2) 三级修复管道，全程可观测 ----------

    /**
     * 拿到<b>原始文本</b>（注意：不用 .entity()，只取 content），交给
     * {@link StructuredOutputRepairer} 三级管道。返回里能看到修复用了哪级策略、
     * 试了几次、原始输出长什么样——修复过程可观测才能统计各级命中率。
     */
    @GetMapping("/lesson15/repair")
    public Map<String, Object> repair(@RequestParam(defaultValue = "CHATTY-JSON") String q) {
        String raw = client.prompt().user(q).call().content();

        // 模型修复函数：把修复提示词发回同一个模型（生产里也常用更便宜的小模型做修复）
        StructuredOutputRepairer.Parsed<OrderInfo> parsed =
                repairer.parse(raw, OrderInfo.class,
                        prompt -> client.prompt().user(prompt).call().content());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", parsed.strategy());
        result.put("attempts", parsed.attempts());
        result.put("value", parsed.value());
        result.put("errorSummary", parsed.errorSummary());
        result.put("rawPreview", raw.length() > 60 ? raw.substring(0, 60) + "..." : raw);
        return result;
    }
}
