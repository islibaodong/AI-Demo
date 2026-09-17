package com.example.demo.lesson26_context;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import com.example.demo.support.ScriptedToolCallingChatModel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 26 课单元测试：不调用任何模型（摘要器注入假函数）。
 * 验证压缩器的触发判断、两级杠杆、保留区不可侵犯、台账记录，
 * 以及 CompactionAdvisor 在真实 ChatClient 管线里的端到端压缩。
 */
class Lesson26ContextTest {

    /** 假摘要器：确定性地证明"中段被摘要过"（真实实现=模型） */
    private static String fakeSummary(List<Message> evicted) {
        return "已做决定：无；已知约束：含 " + evicted.size() + " 条历史；遗留事项：无（FAKE-SUMMARY）";
    }

    /** 构造超预算会话：system + 2 轮（每轮带 1000 字符的工具结果）+ 尾部问答 */
    private static List<Message> transcript() {
        return List.of(
                new SystemMessage("你是售后客服。"),
                new UserMessage("查订单1"),
                AssistantMessage.builder().content("查询中").toolCalls(List.of(
                        new AssistantMessage.ToolCall("t1", "function", "queryOrder", "{}"))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                        "t1", "queryOrder", "DATA-" + "x".repeat(1000)))).build(),
                new AssistantMessage("订单1已发货。"),
                new UserMessage("查订单2"),
                AssistantMessage.builder().content("查询中").toolCalls(List.of(
                        new AssistantMessage.ToolCall("t2", "function", "queryOrder", "{}"))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                        "t2", "queryOrder", "DATA-" + "y".repeat(1000)))).build(),
                new AssistantMessage("订单2配送中。"),
                new UserMessage("发票何时开？"),
                new AssistantMessage("收货后 24 小时内开出。"));
    }

    @Test
    void belowThresholdIsNoOp() {
        SessionCompactor compactor = new SessionCompactor(10_000, 4, Lesson26ContextTest::fakeSummary);
        List<Message> small = transcript().subList(0, 4);

        assertThat(compactor.shouldCompact(small)).isFalse();
        SessionCompactor.Result result = compactor.compact(small);
        assertThat(result.messages()).containsExactlyElementsOf(small);
        assertThat(result.ledger()).isEmpty();
    }

    @Test
    void lever1ClearsOldToolResultsButKeepsStructure() {
        // 预算只够杠杆 1 解决：清旧工具结果，不动中段对话
        SessionCompactor compactor = new SessionCompactor(400, 4, Lesson26ContextTest::fakeSummary);
        SessionCompactor.Result result = compactor.compact(transcript());

        assertThat(result.ledger()).isNotEmpty();
        assertThat(result.ledger().get(0).lever()).isEqualTo("clear-tool-results");
        // 只清 1 条：第二条工具结果落在保留区（最近 4 条内）——保留区不可侵犯
        assertThat(result.ledger().get(0).clearedToolResults()).isEqualTo(1);
        assertThat(result.ledger().get(0).afterTokens())
                .isLessThan(result.ledger().get(0).beforeTokens());

        // 结构保留：ToolResponseMessage 的 id/name 不变（模型 API 的配对不破坏），内容变占位符
        List<ToolResponseMessage> toolMessages = result.messages().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .toList();
        assertThat(toolMessages).hasSize(2);
        assertThat(toolMessages.get(0).getResponses().get(0).responseData())
                .contains("已清理").contains("queryOrder");
        assertThat(toolMessages.get(0).getResponses().get(0).id()).isEqualTo("t1");
        // 保留区里的工具结果原文原样保留
        assertThat(toolMessages.get(1).getResponses().get(0).responseData()).startsWith("DATA-");
        // 结论性的 assistant 消息原样保留（结论早已消化了工具结果）
        assertThat(result.messages()).anySatisfy(m ->
                assertThat(m.getText()).contains("订单1已发货"));
    }

    /** 中段是长文本（无大块工具结果）的会话：杠杆 1 无处发力，只有杠杆 2 能救 */
    private static List<Message> proseTranscript() {
        return List.of(
                new SystemMessage("你是售后客服。"),
                new UserMessage("详细讲讲退货流程，越细越好。" + "背景。".repeat(500)),
                new AssistantMessage("退货流程如下，请仔细阅读：" + "步骤。".repeat(500)),
                new UserMessage("再讲讲换货流程。" + "补充。".repeat(500)),
                new AssistantMessage("换货流程如下：" + "环节。".repeat(500)),
                new UserMessage("发票何时开？"),
                new AssistantMessage("收货后 24 小时内开出。"));
    }

    @Test
    void lever2SummarizesMiddleWhenBudgetTight() {
        // 长文本中段 + 极紧预算：杠杆 1 无工具结果可清，直接触发二级摘要
        SessionCompactor compactor = new SessionCompactor(150, 2, Lesson26ContextTest::fakeSummary);
        SessionCompactor.Result result = compactor.compact(proseTranscript());

        assertThat(result.ledger())
                .anySatisfy(e -> assertThat(e.lever()).isEqualTo("summarize-middle"));
        // 摘要以 [历史摘要] 消息的形式进入上下文
        assertThat(result.messages()).anySatisfy(m ->
                assertThat(m.getText()).contains("[历史摘要]").contains("FAKE-SUMMARY"));

        // 保留区不可侵犯：system 原文在、最近 2 条（发票问答）原文在
        assertThat(result.messages().get(0).getText()).isEqualTo("你是售后客服。");
        assertThat(result.messages().get(result.messages().size() - 1).getText())
                .isEqualTo("收货后 24 小时内开出。");
        // 压缩后确实变小
        assertThat(SessionCompactor.estimateTokens(result.messages()))
                .isLessThanOrEqualTo(SessionCompactor.estimateTokens(proseTranscript()));
    }

    @Test
    void compactionAdvisorCompactsInsideRealChatClientPipeline() {
        // 端到端：压缩 Advisor 挂进 ChatClient，验证模型实际收到的是压缩后的历史
        ChatModel model = new ScriptedToolCallingChatModel(List.of(
                new ChatResponse(List.of(new Generation(new AssistantMessage("好的"))))));
        ScriptedToolCallingChatModel scripted = (ScriptedToolCallingChatModel) model;

        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(new CompactionAdvisor(
                        new SessionCompactor(150, 2, Lesson26ContextTest::fakeSummary), 200))
                .build();

        // 直接把超长历史灌进请求（模拟长会话的最后一次提问）
        String answer = client.prompt()
                .messages(proseTranscript())
                .user("现在发票什么时候能开？")
                .call()
                .content();

        assertThat(answer).isEqualTo("好的");
        // 模型收到的消息数远小于原始 7 条：中段被摘要成一条 [历史摘要]
        List<Message> seen = scripted.observedCalls().get(0).getInstructions();
        assertThat(seen.size()).isLessThan(proseTranscript().size());
        assertThat(seen).anySatisfy(m -> assertThat(m.getText()).contains("[历史摘要]"));
        // 保留区：system 与尾部问答原文仍在
        assertThat(seen.get(0).getText()).isEqualTo("你是售后客服。");
        assertThat(seen.get(seen.size() - 1).getText()).contains("现在发票什么时候能开");
    }
}
