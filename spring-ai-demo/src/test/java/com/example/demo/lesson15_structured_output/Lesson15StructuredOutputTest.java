package com.example.demo.lesson15_structured_output;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 15 课单元测试：不调用任何模型。实测验证 2.0 BeanOutputConverter 的
 * 行为边界（2.0 已内置 markdown 围栏清理），以及三级修复管道的逐级升级。
 */
class Lesson15StructuredOutputTest {

    private final StructuredOutputRepairer repairer = new StructuredOutputRepairer();

    private static final String CLEAN = "{\"orderId\": \"A1001\", \"status\": \"已发货\", \"amount\": 129}";

    // ---------- 2.0 BeanOutputConverter 的行为边界（实测探针） ----------

    @Test
    void directParseAcceptsCleanJson() {
        OrderInfo info = repairer.directParse(CLEAN, OrderInfo.class);
        assertThat(info.orderId()).isEqualTo("A1001");
        assertThat(info.amount()).isEqualTo(129);
    }

    @Test
    void fenceWrappedJsonIsCleanedBy20BuiltIn() {
        // 2.0 实测：BeanOutputConverter 内置 MarkdownCodeBlockCleaner，
        // markdown 围栏包裹不再炸（1.x 需要手动剥）——这是 2.0 的改进点
        OrderInfo info = repairer.directParse("```json\n" + CLEAN + "\n```", OrderInfo.class);
        assertThat(info.orderId()).isEqualTo("A1001");
    }

    @Test
    void chattyWrapperBreaksDirectParse() {
        // 最常见的生产故障：模型的"礼貌"让解析器崩掉
        String chatty = "好的，这是您要的 JSON：" + CLEAN + " 希望有帮助！";
        assertThatThrownBy(() -> repairer.directParse(chatty, OrderInfo.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void truncatedJsonBreaksDirectParse() {
        // maxTokens 设小了：JSON 只剩半截
        assertThatThrownBy(() -> repairer.directParse("{\"orderId\": \"A1001\", \"status\": \"已发", OrderInfo.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void wrongFieldTypeBreaksDirectParse() {
        // 模型把数字写成了汉字——InvalidFormatException
        assertThatThrownBy(() -> repairer.directParse(
                "{\"orderId\": \"A1001\", \"status\": \"已发货\", \"amount\": \"一百二十九\"}", OrderInfo.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    // ---------- 二级：本地抽取 ----------

    @Test
    void extractJsonSalvagesChattyOutput() {
        String chatty = "好的，这是您要的 JSON：" + CLEAN + " 希望有帮助！";
        String extracted = repairer.extractJson(chatty);

        assertThat(extracted).isEqualTo(CLEAN);
        assertThat(repairer.directParse(extracted, OrderInfo.class).orderId()).isEqualTo("A1001");
    }

    @Test
    void extractJsonCannotFixTruncatedOutput() {
        // 截断的 JSON 花括号不配对，本地抽取无能为力——只能升级到模型修复
        assertThat(repairer.extractJson("{\"orderId\": \"A1001\", \"stat"))
                .isNull();
    }

    @Test
    void extractJsonReturnsNullWhenNoBraces() {
        assertThat(repairer.extractJson("没有任何 JSON 的纯文本")).isNull();
        assertThat(repairer.extractJson(null)).isNull();
    }

    // ---------- 三级管道：逐级升级 ----------

    @Test
    void pipelineTakesDirectPathForCleanJsonWithoutCallingModel() {
        AtomicInteger modelCalls = new AtomicInteger();

        StructuredOutputRepairer.Parsed<OrderInfo> parsed = repairer.parse(
                CLEAN, OrderInfo.class, prompt -> {
                    modelCalls.incrementAndGet();
                    return CLEAN;
                });

        assertThat(parsed.strategy()).isEqualTo(StructuredOutputRepairer.Strategy.DIRECT);
        assertThat(parsed.attempts()).isEqualTo(1);
        assertThat(parsed.value().amount()).isEqualTo(129);
        assertThat(modelCalls.get()).isZero();   // 干净输出绝不浪费修复调用
    }

    @Test
    void pipelineRepairsChattyOutputLocally() {
        String chatty = "好的，这是您要的 JSON：" + CLEAN + " 希望有帮助！";
        AtomicInteger modelCalls = new AtomicInteger();

        StructuredOutputRepairer.Parsed<OrderInfo> parsed = repairer.parse(
                chatty, OrderInfo.class, prompt -> {
                    modelCalls.incrementAndGet();
                    return CLEAN;
                });

        // 二级 EXTRACT 就救回来了：没调模型、零额外成本
        assertThat(parsed.strategy()).isEqualTo(StructuredOutputRepairer.Strategy.EXTRACT);
        assertThat(parsed.attempts()).isEqualTo(2);
        assertThat(parsed.value().orderId()).isEqualTo("A1001");
        assertThat(modelCalls.get()).isZero();
    }

    @Test
    void pipelineEscalatesTruncatedOutputToModelRepair() {
        AtomicInteger modelCalls = new AtomicInteger();

        StructuredOutputRepairer.Parsed<OrderInfo> parsed = repairer.parse(
                "{\"orderId\": \"A1001\", \"status\": \"已发", OrderInfo.class, prompt -> {
                    modelCalls.incrementAndGet();
                    // 修复提示词里应包含坏输出与解析错误——错误信息越具体，修得越准
                    assertThat(prompt).contains("已发", "JSON");
                    return CLEAN;
                });

        assertThat(parsed.strategy()).isEqualTo(StructuredOutputRepairer.Strategy.MODEL_REPAIR);
        assertThat(parsed.attempts()).isEqualTo(3);
        assertThat(parsed.value()).isEqualTo(new OrderInfo("A1001", "已发货", 129));
        assertThat(modelCalls.get()).isEqualTo(1);
    }

    @Test
    void pipelineReturnsFailedInsteadOfThrowingWhenAllLevelsFail() {
        // 模型修复也没救回来：管道不抛异常，返回 FAILED + 错误摘要，
        // 由调用方决定降级（默认值/人工审核）
        StructuredOutputRepairer.Parsed<OrderInfo> parsed = repairer.parse(
                "{\"orderId\": \"A1001\", \"stat", OrderInfo.class, prompt -> "我也修不好，这不是 JSON");

        assertThat(parsed.strategy()).isEqualTo(StructuredOutputRepairer.Strategy.FAILED);
        assertThat(parsed.value()).isNull();
        assertThat(parsed.errorSummary()).isNotBlank();
    }

    @Test
    void repairPromptContainsBrokenOutputAndError() {
        String prompt = repairer.repairPrompt("坏输出", "Unexpected end-of-input");
        assertThat(prompt).contains("坏输出").contains("Unexpected end-of-input").contains("只输出");
    }
}
