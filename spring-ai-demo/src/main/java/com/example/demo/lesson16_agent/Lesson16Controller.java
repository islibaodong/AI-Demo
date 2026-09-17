package com.example.demo.lesson16_agent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 16 课：多步 Agent 编排 —— 模型自主串起多步工具调用，解决真实业务场景。
 *
 * <p><b>业务场景：售后工单自动处理</b>。用户一句话"订单 A1001 有质量问题要退款"，
 * Agent 自主完成：{@code queryOrder → checkRefundPolicy → applyRefund（被审批门拦截）→
 * createTicket → notifyUser → 总结}。单次工具调用（lesson05/10）到多步自主编排，
 * 差的不是 API，是<b>循环 + 治理</b>。</p>
 *
 * <p><b>两个端点对照：</b></p>
 * <ul>
 *   <li>{@code /lesson16/auto}：<b>框架内置循环</b>——{@code .toolCallbacks(...)} 一行搞定，
 *       Spring AI 自动"模型 → 执行工具 → 结果喂回 → 再问模型"直到最终答复。
 *       简单，但黑盒：调了什么工具看不见，且循环无步数上限。</li>
 *   <li>{@code /lesson16/agent}：<b>治理版</b>——同样的循环，但每个工具都包了
 *       {@link GovernedTool} 装饰器（共享一个 {@link AgentGovernor}）：
 *       审计轨迹、工具调用预算、高危审批门三件套。返回里带完整 toolCalls 轨迹，
 *       能看到 applyRefund 被拦截后模型自主改道 createTicket。</li>
 * </ul>
 *
 * <p><b>关键教学点</b>：Agent 的可控性不在模型，在<b>工具执行层</b>——
 * 模型只负责"决定调什么"，执行永远过你的手（lesson13 最小权限是事前控制，
 * 本课审批门/预算是事中控制，lesson14 指标是事后观测，三层合起来才是生产 Agent）。</p>
 *
 * <p><b>与 LangChain 对照</b>：内置循环 ≈ AgentExecutor（create_tool_agent）；
 * 治理层 ≈ LangGraph 的 human-in-the-loop + ToolNode 定制。Java 侧没有
 * LangGraph 的图编排，Spring AI 的思路是"框架跑循环 + 装饰器管执行"。</p>
 *
 * <p>试试：</p>
 * <ul>
 *   <li><code>curl "localhost:8080/lesson16/auto?q=订单 A1001 有质量问题要退款"</code> —— 自动循环，只看到最终答案</li>
 *   <li><code>curl "localhost:8080/lesson16/agent?q=订单 A1001 有质量问题要退款"</code> —— 完整工具轨迹：含退款被拦、改道建工单</li>
 * </ul>
 */
@RestController
public class Lesson16Controller {

    /** Agent 系统提示：交代角色 + 工具使用策略（生产里这段是 Agent 质量的关键） */
    static final String AGENT_SYSTEM = """
            你是售后客服 Agent。处理用户的售后请求时按需调用工具：
            先查订单，再核对售后政策，然后按政策执行处理（退款需要人工审批，被拒绝时改为创建工单转人工），
            处理完成后通知用户，最后用一句话向用户总结结果。不要编造订单信息。""";

    static final int DEFAULT_MAX_TOOL_CALLS = 6;
    static final String DEFAULT_APPROVALS = "applyRefund";

    private final ChatClient client;

    public Lesson16Controller(ChatModel chatModel) {
        this.client = ChatClient.builder(chatModel).build();
    }

    // ---------- 1) 框架内置循环（黑盒对比版） ----------

    /**
     * 一行 {@code .toolCallbacks(...)}：Spring AI 内置循环自动执行工具并喂回模型。
     * 只返回最终答案——中间调了几次工具、参数是什么，业务代码一概看不见。
     */
    @GetMapping("/lesson16/auto")
    public String auto(@RequestParam(defaultValue = "订单 A1001 有质量问题要退款") String q) {
        return client.prompt()
                .system(AGENT_SYSTEM)
                .user(q)
                .toolCallbacks(rawTools())
                .call()
                .content();
    }

    // ---------- 2) 治理版：审计 + 预算 + 审批门 ----------

    /**
     * 同样的内置循环，但工具全部包上 {@link GovernedTool}：
     * 返回完整工具调用轨迹。注意看 applyRefund 被[拦截]后，
     * 模型读到"需人工审批"的工具结果，自主改道 createTicket——
     * 治理不靠报错炸链路，靠把约束写进工具结果让模型配合。
     */
    @GetMapping("/lesson16/agent")
    public Map<String, Object> agent(
            @RequestParam(defaultValue = "订单 A1001 有质量问题要退款") String q,
            @RequestParam(defaultValue = "6") int maxToolCalls,
            @RequestParam(defaultValue = DEFAULT_APPROVALS) String approvals) {

        Set<String> approvalSet = parseApprovals(approvals);
        AgentGovernor governor = new AgentGovernor(maxToolCalls, approvalSet);
        ToolCallback[] governed = Arrays.stream(rawTools())
                .map(t -> (ToolCallback) new GovernedTool(governor, t))
                .toArray(ToolCallback[]::new);

        String answer = client.prompt()
                .system(AGENT_SYSTEM)
                .user(q)
                .toolCallbacks(governed)
                .call()
                .content();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("toolCalls", governor.auditLog());
        result.put("executedToolCalls", governor.executedCalls());
        result.put("maxToolCalls", governor.maxToolCalls());
        result.put("approvalRequired", approvalSet);
        return result;
    }

    // ---------- 工具方法（包级可见以便离线单测复用） ----------

    private static ToolCallback[] rawTools() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(new AfterSaleTools())
                .build()
                .getToolCallbacks();
    }

    /** 逗号分隔的高危工具名集合（"applyRefund,deleteOrder" 这种形式） */
    static Set<String> parseApprovals(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 治理装饰器：模型看到的工具定义不变（{@link #getToolDefinition()} 直接委托），
     * 但 call() 被接管——所有 GovernedTool 共享同一个 {@link AgentGovernor}，
     * 预算与审计跨工具累计（这才是"这次 Agent 任务"的真实用量）。
     */
    public static class GovernedTool implements ToolCallback {

        private final AgentGovernor governor;
        private final ToolCallback real;

        public GovernedTool(AgentGovernor governor, ToolCallback real) {
            this.governor = governor;
            this.real = real;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return real.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            return governor.governCall(toolInput, real);
        }

        /** 框架在带 ToolContext 时走这个重载——两个入口必须走同一套治理逻辑 */
        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return governor.governCall(toolInput, real);
        }
    }
}
