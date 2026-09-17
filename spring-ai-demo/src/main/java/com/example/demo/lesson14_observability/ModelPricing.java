package com.example.demo.lesson14_observability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.ai.chat.metadata.Usage;

/**
 * 第 14 课：模型单价表与成本估算。
 *
 * <p><b>为什么成本要单独成课？</b>LLM 应用最隐蔽的生产事故是"账单爆炸"：
 * 单次调用几分钱，乘上 QPS × 会话轮数 × 上下文膨胀，一个月就是真金白银。
 * 可观测的第一步是把"每次调用花了多少钱"变成可查询的数字。</p>
 *
 * <p><b>计价的三个事实</b>：</p>
 * <ul>
 *   <li><b>prompt 与 completion 分开计价</b>，输出单价通常是输入的 3~4 倍——
 *       所以"让模型少废话"是比"换便宜模型"更快的省钱手段。</li>
 *   <li>单价按<b>每 100 万 token</b> 报价，除以 1_000_000 才是单 token 成本。</li>
 *   <li>本表是 OpenAI 官方牌价（USD，每 1M token）；中转站/云厂商折扣价不同，
 *       生产里把价目挪到配置文件即可，估算逻辑不变。</li>
 * </ul>
 *
 * <p><b>金额为什么用 BigDecimal 不用 double</b>：0.1 + 0.2 != 0.3 是浮点运算的
 * 经典坑，累计成本时误差会越滚越大。金额一律 BigDecimal + 显式精度。</p>
 *
 * <p><b>与 LangChain 对照</b>：LangChain 没有内置成本核算，社区常用
 * langchain-community 的 get_openai_callback 统计；Spring AI 把原始用量放在
 * {@link Usage} 里（本课的 Controller 演示），
 * 换算成钱这一步需要自己做——本类就是那个"自己做的最小实现"。</p>
 */
public record ModelPricing(String model, double promptPerMillion, double completionPerMillion) {

    /** OpenAI 官方牌价（USD / 1M tokens，2025 年公开价，仅教学演示用） */
    public static final ModelPricing GPT_4O = new ModelPricing("gpt-4o", 2.50, 10.00);
    public static final ModelPricing GPT_4O_MINI = new ModelPricing("gpt-4o-mini", 0.15, 0.60);

    /**
     * 匹配顺序的坑：contains 模糊匹配时<b>长名必须在前</b>——
     * "gpt-4o-mini" 也包含 "gpt-4o"，先查 gpt-4o 会把 mini 误判成 4o（贵 16 倍）。
     */
    private static final List<ModelPricing> ALL = List.of(GPT_4O_MINI, GPT_4O);

    /**
     * 按模型名查价。模型名可能带后缀（如 "gpt-4o-2024-11-20"），所以用 contains 模糊匹配。
     * 未知型号按最贵的 gpt-4o 估——<b>成本估算宁可高估</b>，低估会让预算告警形同虚设。
     */
    public static ModelPricing of(String model) {
        for (ModelPricing p : ALL) {
            if (model != null && model.contains(p.model())) {
                return p;
            }
        }
        return GPT_4O;
    }

    /**
     * 单次调用的成本拆解。三个金额都保留 6 位小数（单价除到单 token 后会出现
     * 0.0000025 这种量级），展示时再按需格式化。
     */
    public record Cost(BigDecimal promptCost, BigDecimal completionCost, BigDecimal totalCost) {
    }

    /** 用响应元数据里的真实用量（{@link Usage}）估算本次成本 */
    public Cost estimate(Usage usage) {
        BigDecimal promptCost = usd(usage.getPromptTokens(), promptPerMillion);
        BigDecimal completionCost = usd(usage.getCompletionTokens(), completionPerMillion);
        return new Cost(promptCost, completionCost, promptCost.add(completionCost));
    }

    /** tokens × 单价 ÷ 1_000_000，HALF_UP 保留 6 位小数 */
    private static BigDecimal usd(Integer tokens, double perMillion) {
        if (tokens == null || tokens <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens)
                .multiply(BigDecimal.valueOf(perMillion))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }
}
