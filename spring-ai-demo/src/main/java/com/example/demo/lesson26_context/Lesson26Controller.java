package com.example.demo.lesson26_context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 26 课：上下文工程与压缩 —— 长会话不减质量、不涨成本的关键。
 *
 * <p>三层递进的演示：</p>
 * <ol>
 *   <li>{@code /lesson26/compact}：对一段人为构造的超长会话（含大块工具结果）
 *       跑 {@link SessionCompactor}，返回压缩前后的 token、台账（清了几条工具结果/
 *       摘要了几条消息）和压缩后的消息清单——两级杠杆的效果一目了然；</li>
 *   <li>{@code /lesson26/chat}：压缩 Advisor 挂进真实 ChatClient
 *       （与 MessageChatMemoryAdvisor 共存——压缩排在记忆装配之后），
 *       疯狂聊到超过预算，看它自动触发；</li>
 *   <li>台账（ledger）进 advisor context——生产上这就是可观测性的数据源
 *       （lesson14 的 Micrometer 可以直接消费）。</li>
 * </ol>
 *
 * <p>试试：</p>
 * <pre>
 * curl "localhost:8080/lesson26/compact"                      # 看两级杠杆的压缩过程与台账
 * curl "localhost:8080/lesson26/compact?budget=800"           # 预算更紧 → 触发二级摘要
 * for i in 1 2 3 4 5 6; do curl "localhost:8080/lesson26/chat?session=s1&q=第$i个问题，请详细回答"; done
 * </pre>
 */
@RestController
public class Lesson26Controller {

    private final ChatClient compactingClient;

    public Lesson26Controller(ChatModel chatModel) {
        ChatMemory window = MessageWindowChatMemory.builder().maxMessages(50).build();
        this.compactingClient = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        // 记忆先装配（默认 order），压缩后执行（order 200 > 记忆默认值）
                        MessageChatMemoryAdvisor.builder(window).build(),
                        new CompactionAdvisor(new SessionCompactor(1200, 4, this::summarize), 200))
                .build();
    }

    // ---------- 1) 压缩演示：两级杠杆 + 台账 ----------

    /** 构造一段超长会话：system + 多轮对话，其中两轮带几千字符的工具结果 */
    static List<Message> longTranscript() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("你是售后客服。"));
        for (int i = 1; i <= 3; i++) {
            history.add(new UserMessage("帮我查一下订单 %d 的物流".formatted(i)));
            history.add(AssistantMessage.builder()
                    .content("好的，我来查询订单 %d 的物流。".formatted(i))
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-%d".formatted(i), "function", "queryLogistics", "{}")))
                    .build());
            // 工具结果：真实物流 JSON 轻松上千 token，结论只有一句话
            history.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("call-%d".formatted(i), "queryLogistics",
                            ("{\"orderId\":\"A10%02d\",\"events\":" + "[{\"time\":\"2026-09-0%d 09:0%d\",\"desc\":\"包裹到达转运中心\"},"
                                    + "{\"time\":\"2026-09-0%d 14:2%d\",\"desc\":\"离开转运中心，发往下一站\"},"
                                    + "{\"time\":\"2026-09-0%d 21:4%d\",\"desc\":\"派送中，快递员联系电话13x****\"}]"
                                    + ",\"rawTracking\":\"%s\"}")
                                    .formatted(i, i, i, i, i, i, i, i, "TRK-2026-%06d-XYZ-%s".formatted(i * 1111, "d".repeat(120))))))
                    .build());
            history.add(new AssistantMessage("订单 %d 已于昨天发出，预计明晚送达。".formatted(i)));
        }
        history.add(new UserMessage("发票什么时候能开？"));
        history.add(new AssistantMessage("收货后 24 小时内开出电子发票。"));
        return history;
    }

    @GetMapping("/lesson26/compact")
    public Map<String, Object> compact(
            @RequestParam(defaultValue = "600") int budget,
            @RequestParam(defaultValue = "3") int keepRecent) {

        List<Message> transcript = longTranscript();
        // 摘要器用模型（生产形态）；压缩器是纯 Java——离线测试注入假函数即可
        SessionCompactor compactor = new SessionCompactor(budget, keepRecent, this::summarize);
        SessionCompactor.Result result = compactor.compact(transcript);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("beforeTokens", SessionCompactor.estimateTokens(transcript));
        body.put("afterTokens", SessionCompactor.estimateTokens(result.messages()));
        body.put("budget", budget);
        body.put("ledger", result.ledger());
        body.put("compactedMessages", result.messages().stream()
                .map(m -> {
                    String text = m.getText() == null ? "" : m.getText();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("type", m.getMessageType());
                    row.put("preview", text.length() > 60 ? text.substring(0, 60) + "…" : text);
                    return row;
                })
                .toList());
        body.put("note", "杠杆 1（清旧工具结果）几乎零成本且不丢结论；杠杆 2（摘要中段）才需要花模型的钱。"
                + "预算再紧就会看到二级摘要触发。");
        return body;
    }

    // ---------- 2) 真实 ChatClient 里的自动压缩 ----------

    /** 聊到超过预算时，压缩 Advisor 自动触发（无需调用方感知） */
    @GetMapping("/lesson26/chat")
    public Map<String, Object> chat(
            @RequestParam(defaultValue = "s1") String session,
            @RequestParam(defaultValue = "退货政策是什么？") String q) {

        String answer = compactingClient.prompt()
                .user(q)
                .advisors(a -> a.param("chat_memory_conversation_id", session))
                .call()
                .content();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("budgetTokens", 1200);
        result.put("note", "会话超过 1200 token 预算时自动压缩（保留 system + 最近 4 条原文）。"
                + "反复聊长内容后观察日志中的 [Compaction] 台账。");
        return result;
    }

    // ---------- 摘要器（生产形态：模型生成结构化摘要） ----------

    /** 按 schema 摘要：决定/约束/遗留——不写散文（散文摘要会淹没"任务已完成"信号） */
    private String summarize(List<Message> evicted) {
        String joined = evicted.stream()
                .map(m -> m.getMessageType() + ": " + (m.getText() == null ? "(工具结果)" : m.getText()))
                .reduce("", (a, b) -> a + "\n" + b);
        return compactingClient.prompt()
                .system("""
                        把下面的对话历史压成一段结构化摘要，严格按三节输出（没有的写"无"）：
                        [已做决定] ...
                        [已知约束] ...
                        [遗留事项] ...
                        保留所有数字与订单号。总长不超过 120 字。""")
                .user(joined)
                .call()
                .content();
    }
}
