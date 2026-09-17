package com.example.demo.lesson26_context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 第 26 课：会话压缩器（compaction）—— 上下文工程的核心算法，手写讲透。
 *
 * <p>会话越长，模型对全历史的注意力越差（context rot），成本还线性上涨。
 * 压缩的目的是<b>把上下文里"值钱的部分"留下来，把陈旧的大块头清理掉</b>。
 * 两级杠杆，<b>便宜的先用</b>（JetBrains 实测：仅第一级就省 52% 成本且准确率反升）：</p>
 *
 * <ol>
 *   <li><b>清理旧工具结果</b>：老轮次的 ToolResponse 常是几千 token 的原文
 *       （查询结果、文件内容），结论早已被 assistant 消息消化——替换成占位符，
 *       结构不动（ToolResponseMessage 的 id/name 保留，模型 API 的配对不破坏）；</li>
 *   <li><b>结构化摘要中段</b>：仍超预算才把中间轮次交给 summarizer（生产=模型，
 *       测试=注入假函数）压成一条摘要——摘要按 schema 写（做了什么决定/有什么约束/
 *       遗留什么），不写散文（纯散文摘要反而淹没"任务已完成"信号）。</li>
 * </ol>
 *
 * <p><b>保留区（绝不压缩）</b>：开头的 system 消息 + 最近 {@code keepRecent} 条消息——
 * 最近几轮是模型"正在处理"的现场，压掉它们等于失忆。</p>
 *
 * <p><b>ledger（压缩台账）</b>：每次压缩记录 压缩前/后 token、清了几条工具结果、
 * 摘要了几条消息——线上排障时，"压缩后答非所问"能定位到具体丢了什么信息。</p>
 *
 * <p><b>与 LangChain 对照</b>：≈ LangGraph Session API / Anthropic Compaction 的
 * 思路（触发阈值 ~60-70% 预算 → 清工具结果 → 结构化摘要）。教学版刻意简化：
 * 真实实现要保证 toolCall/toolResponse 配对完整、并发会话隔离——
 * Spring AI 的 Session API（计划随 2.1）是生产级答案。</p>
 */
public class SessionCompactor {

    /** 摘要器：把被驱逐的中段消息压成一段结构化摘要（生产实现=模型调用） */
    @FunctionalInterface
    public interface Summarizer {
        String summarize(List<Message> evicted);
    }

    /** 一次压缩动作的台账记录 */
    public record CompactionEvent(
            String lever,
            int beforeTokens,
            int afterTokens,
            int clearedToolResults,
            int summarizedMessages) {
    }

    /** 压缩结果：压缩后的消息序列 + 台账 */
    public record Result(List<Message> messages, List<CompactionEvent> ledger) {
    }

    /** 粗略 token 估算：~4 字符/token（与 lesson14 的假中转站口径一致） */
    static final int CHARS_PER_TOKEN = 4;

    private final int budgetTokens;
    private final int keepRecent;
    private final Summarizer summarizer;

    public SessionCompactor(int budgetTokens, int keepRecent, Summarizer summarizer) {
        this.budgetTokens = budgetTokens;
        this.keepRecent = keepRecent;
        this.summarizer = summarizer;
    }

    /** 触发判断：估算 token 是否超过预算（生产建议在 60~70% 预算就触发，别等到溢出） */
    public boolean shouldCompact(List<Message> history) {
        return estimateTokens(history) > budgetTokens;
    }

    /** 执行压缩：循环套用两级杠杆直到低于预算或无事可做 */
    public Result compact(List<Message> history) {
        List<Message> messages = new ArrayList<>(history);
        List<CompactionEvent> ledger = new ArrayList<>();

        int guard = 0;
        while (estimateTokens(messages) > budgetTokens && guard++ < 5) {
            // 保留区：开头连续的 system 消息 + 尾部最近 keepRecent 条
            int head = 0;
            while (head < messages.size() && messages.get(head) instanceof SystemMessage) {
                head++;
            }
            int keepFrom = Math.max(head, messages.size() - keepRecent);

            // —— 杠杆 1：清理保留区之外的旧工具结果（结构不变，内容换占位符）——
            int before = estimateTokens(messages);
            List<Message> cleared = new ArrayList<>();
            int clearedCount = 0;
            for (int i = 0; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (i >= head && i < keepFrom && m instanceof ToolResponseMessage trm) {
                    // 构造器是 protected 的，公开路径走 builder（2.0 实测）
                    cleared.add(ToolResponseMessage.builder()
                            .responses(trm.getResponses().stream()
                                    .map(r -> new ToolResponseMessage.ToolResponse(
                                            r.id(), r.name(), "[已清理：旧工具结果 " + r.name() + "]"))
                                    .toList())
                            .build());
                    clearedCount++;
                }
                else {
                    cleared.add(m);
                }
            }
            messages = cleared;
            if (clearedCount > 0) {
                ledger.add(new CompactionEvent("clear-tool-results", before,
                        estimateTokens(messages), clearedCount, 0));
            }
            if (estimateTokens(messages) <= budgetTokens) {
                break;
            }

            // —— 杠杆 2：把中段（除 system 与保留区）交给摘要器 ——
            before = estimateTokens(messages);
            List<Message> middle = new ArrayList<>(messages.subList(head, keepFrom));
            if (middle.size() < 2) {
                break;  // 没有可摘要的中段了，放弃（下轮循环退出）
            }
            String summary = summarizer.summarize(middle);
            List<Message> summarized = new ArrayList<>();
            summarized.addAll(messages.subList(0, head));
            summarized.add(new UserMessage("[历史摘要] " + summary));
            summarized.addAll(messages.subList(keepFrom, messages.size()));
            messages = summarized;
            ledger.add(new CompactionEvent("summarize-middle", before,
                    estimateTokens(messages), 0, middle.size()));
        }
        return new Result(List.copyOf(messages), List.copyOf(ledger));
    }

    /**
     * 粗略 token 估算。注意：{@code getText()} 对工具消息返回空串——
     * 工具结果的真实载荷在 {@code getResponses()} 的 responseData 里，
     * 漏掉它会让压缩器永远不触发（实测踩过）。
     */
    static int estimateTokens(List<Message> messages) {
        return messages.stream()
                .mapToInt(m -> {
                    int chars = m.getText() == null ? 0 : m.getText().length();
                    if (m instanceof ToolResponseMessage trm) {
                        chars += trm.getResponses().stream()
                                .mapToInt(r -> r.responseData() == null ? 0 : r.responseData().length())
                                .sum();
                    }
                    if (m instanceof AssistantMessage am) {
                        chars += am.getToolCalls().stream()
                                .mapToInt(tc -> tc.arguments() == null ? 0 : tc.arguments().length())
                                .sum();
                    }
                    return chars / CHARS_PER_TOKEN;
                })
                .sum();
    }
}
