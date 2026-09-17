package com.example.demo.lesson14_observability;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.observation.ModelUsageMetricsGenerator;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 14 课单元测试：不调用任何模型。覆盖成本估算、Spring AI 内置的
 * GenAI 指标生成器、预算防护 Advisor 的累计与短路、maxTokens 选项绑定。
 */
class Lesson14ObservabilityTest {

    private final AtomicInteger downstreamCalls = new AtomicInteger();

    private static ChatClientResponse answer(String text, Usage usage) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(usage)
                .model("fake-model")
                .build();
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata),
                Map.of());
    }

    private static ChatClientRequest request(String text) {
        return new ChatClientRequest(new Prompt(new UserMessage(text)), Map.of());
    }

    // ---------- 成本估算 ----------

    @Test
    void costEstimateMatchesPriceTable() {
        // gpt-4o 牌价：prompt $2.50 / 1M，completion $10.00 / 1M
        Usage usage = new DefaultUsage(1_000, 500, 1_500);
        ModelPricing.Cost cost = ModelPricing.GPT_4O.estimate(usage);

        assertThat(cost.promptCost()).isEqualByComparingTo(new BigDecimal("0.002500"));
        assertThat(cost.completionCost()).isEqualByComparingTo(new BigDecimal("0.005000"));
        assertThat(cost.totalCost()).isEqualByComparingTo(new BigDecimal("0.007500"));
    }

    @Test
    void completionIsPricedHigherThanPrompt() {
        // 同样 1000 token：输出侧成本是输入侧的 4 倍（10.00 vs 2.50）——
        // 这就是"给输出封顶比换模型更省钱"的数学依据
        Usage usage = new DefaultUsage(1_000, 1_000, 2_000);
        ModelPricing.Cost cost = ModelPricing.GPT_4O.estimate(usage);

        assertThat(cost.completionCost())
                .isEqualTo(cost.promptCost().multiply(new BigDecimal("4")));
    }

    @Test
    void miniIsCheaperThan4oForSameUsage() {
        Usage usage = new DefaultUsage(1_000, 500, 1_500);
        ModelPricing.Cost fourOh = ModelPricing.GPT_4O.estimate(usage);
        ModelPricing.Cost mini = ModelPricing.GPT_4O_MINI.estimate(usage);

        assertThat(mini.totalCost()).isLessThan(fourOh.totalCost());
    }

    @Test
    void unknownModelFallsBackToMostExpensivePrice() {
        // 成本估算宁可高估：未知型号（带日期后缀的除外）按最贵的 gpt-4o 估
        assertThat(ModelPricing.of("some-unknown-model")).isEqualTo(ModelPricing.GPT_4O);
        // 版本后缀要能模糊匹配上
        assertThat(ModelPricing.of("gpt-4o-2024-11-20")).isEqualTo(ModelPricing.GPT_4O);
        assertThat(ModelPricing.of("gpt-4o-mini-2024-07-18")).isEqualTo(ModelPricing.GPT_4O_MINI);
    }

    @Test
    void nullTokensAreCountedAsZero() {
        ModelPricing.Cost cost = ModelPricing.GPT_4O.estimate(new DefaultUsage(null, null, null));
        assertThat(cost.totalCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---------- Spring AI 内置 GenAI 指标生成器 ----------

    @Test
    void builtInGeneratorRecordsThreeTokenTypeCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        ModelUsageMetricsGenerator.generate(
                new DefaultUsage(100, 40, 140), new Observation.Context(), registry);

        // 2.0 按语义约定 gen_ai.client.token.usage 注册 3 条计数器，
        // tag gen_ai.token.type 区分 input / output / total
        assertThat(registry.get(Lesson14Controller.TOKEN_USAGE_METRIC)
                .tag(Lesson14Controller.TOKEN_TYPE_TAG, "input").counter().count()).isEqualTo(100.0);
        assertThat(registry.get(Lesson14Controller.TOKEN_USAGE_METRIC)
                .tag(Lesson14Controller.TOKEN_TYPE_TAG, "output").counter().count()).isEqualTo(40.0);
        assertThat(registry.get(Lesson14Controller.TOKEN_USAGE_METRIC)
                .tag(Lesson14Controller.TOKEN_TYPE_TAG, "total").counter().count()).isEqualTo(140.0);
    }

    // ---------- 预算防护 Advisor ----------

    @Test
    void budgetAdvisorAccumulatesUsageAndBlocksWhenExhausted() {
        CallAdvisorChain chain = new CallAdvisorChain() {
            @Override
            public ChatClientResponse nextCall(ChatClientRequest request) {
                downstreamCalls.incrementAndGet();
                return answer("模型的回答", new DefaultUsage(60, 30, 90));
            }
            @Override public List<CallAdvisor> getCallAdvisors() { throw new UnsupportedOperationException(); }
            @Override public CallAdvisorChain copy(CallAdvisor advisor) { throw new UnsupportedOperationException(); }
        };
        TokenBudgetAdvisor budget = new TokenBudgetAdvisor(100);

        // 第一次：90 token < 100 上限，放行
        ChatClientResponse first = budget.adviseCall(request("你好"), chain);
        assertThat(first.chatResponse().getResult().getOutput().getText()).isEqualTo("模型的回答");
        assertThat(budget.usedTokens()).isEqualTo(90);

        // 第二次：已用 90 ≥ 100？未到——但 90 + 90 = 180 会在第三次超。这里先验证累计
        budget.adviseCall(request("再问一次"), chain);
        assertThat(budget.usedTokens()).isEqualTo(180);

        // 第三次：预算耗尽，短路——模型一次都没被多调用，本次零成本
        ChatClientResponse third = budget.adviseCall(request("还想再问"), chain);
        assertThat(third.chatResponse().getResult().getOutput().getText()).contains("预算防护");
        assertThat(downstreamCalls.get()).isEqualTo(2);   // 第三次没走到模型
        assertThat(budget.remaining()).isZero();
    }

    @Test
    void budgetAdvisorHandlesMissingUsageGracefully() {
        // API 没返回用量时按 0 处理——防护本身绝不能把业务调用搞挂
        CallAdvisorChain chain = new CallAdvisorChain() {
            @Override
            public ChatClientResponse nextCall(ChatClientRequest request) {
                return new ChatClientResponse(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("无用量")))),
                        Map.of());
            }
            @Override public List<CallAdvisor> getCallAdvisors() { throw new UnsupportedOperationException(); }
            @Override public CallAdvisorChain copy(CallAdvisor advisor) { throw new UnsupportedOperationException(); }
        };
        TokenBudgetAdvisor budget = new TokenBudgetAdvisor(10);

        budget.adviseCall(request("你好"), chain);

        assertThat(budget.usedTokens()).isZero();
    }

    // ---------- maxTokens 输出上限选项 ----------

    @Test
    void maxTokensOptionIsBoundIntoRequestOptions() {
        OpenAiChatOptions options = OpenAiChatOptions.builder().maxTokens(64).build();
        assertThat(options.getMaxTokens()).isEqualTo(64);
    }
}
