package com.example.demo.lesson21_graph;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 21 课：工作流编排 —— 显式 loop、状态图、人在环中。
 *
 * <p>三个端点对应三个概念：</p>
 * <ol>
 *   <li><b>loop</b>（{@code /lesson21/loop}）：写文案 → 打分 → 不达标改写 → 再打分的
 *       迭代循环。与 lesson16 的内置工具循环不同，这个循环是<b>业务定义</b>的
 *       （节点与路由自己画），并且有显式的轮数上限 + 引擎步数硬顶双保险；</li>
 *   <li><b>graph</b>（{@code /lesson21/graph/run}）：退款审批工作流——分类节点按意图
 *       路由（退款走审批分支、其他走 FAQ），trace 字段能看到完整执行路径；</li>
 *   <li><b>人在环中</b>（{@code /lesson21/graph/approve/...}）：审批节点挂起执行
 *       （返回 executionId），人工在<b>另一个请求</b>里批准/拒绝，resume 从挂起节点
 *       重入，走对应分支收尾。对照 lesson16 的"审批门拦截改道"：那边模型在场自主改道
 *       （软治理），这边流程冻结等真人决定（硬管控）。</li>
 * </ol>
 *
 * <p>试试：</p>
 * <pre>
 * # 1) 迭代循环：观察 rounds 与 trace
 * curl "localhost:8080/lesson21/loop?topic=机械键盘促销"
 * # 2) 图：退款请求 → 挂起（suspended + executionId）
 * curl "localhost:8080/lesson21/graph/run?q=订单A1001要退款"
 * # 3) 人工批准 → 恢复执行（换拒绝试试 approved=false）
 * curl -X POST "localhost:8080/lesson21/graph/approve/&lt;executionId&gt;?approved=true&comment=质量问题属实"
 * # 4) 待审批列表
 * curl "localhost:8080/lesson21/graph/pending"
 * </pre>
 */
@RestController
public class Lesson21Controller {

    /** 循环演示的最大轮数（业务级上限；引擎 maxSteps 是第二道硬顶） */
    static final int MAX_REVIEW_ROUNDS = 3;
    /** 打分达标线（1-10 分制） */
    static final int PASS_SCORE = 8;

    private final ChatClient client;
    /** 审批工作流：单实例共享（节点无状态），checkpoint 跨请求存续 */
    private final CompiledGraph approvalWorkflow;

    public Lesson21Controller(ChatModel chatModel) {
        this.client = ChatClient.builder(chatModel).build();
        this.approvalWorkflow = RefundApprovalWorkflow.graph().compile(new CheckpointStore());
    }

    // ---------- 1) 显式迭代循环：draft → review →(不达标)→ revise → review ... ----------

    @GetMapping("/lesson21/loop")
    public Map<String, Object> loop(@RequestParam(defaultValue = "机械键盘促销") String topic) {
        StateGraph loopGraph = new StateGraph()
                .addNode("draft", state -> {
                    state.put("draft", client.prompt()
                            .system("你是营销文案写手，只输出文案本身，不要解释。")
                            .user("为主题「%s」写一句 20 字以内的促销文案".formatted(state.get("topic")))
                            .call()
                            .content());
                    return state;
                })
                .addNode("review", state -> {
                    String raw = client.prompt()
                            .system("你是文案评审。只输出一个 1-10 的整数评分，不要输出任何其他内容。")
                            .user("给这句文案打分：\n%s".formatted(state.get("draft")))
                            .call()
                            .content();
                    state.put("score", parseScore(raw));
                    return state;
                })
                .addNode("revise", state -> {
                    state.put("draft", client.prompt()
                            .system("你是营销文案写手，只输出改写后的文案本身。")
                            .user(("这句文案评审只打了 %s 分：%s\n请改写得更抓人，主题是「%s」，20 字以内。")
                                    .formatted(state.get("score"), state.get("draft"), state.get("topic")))
                            .call()
                            .content());
                    state.put("round", ((Integer) state.getOrDefault("round", 0)) + 1);
                    return state;
                })
                .entry("draft")
                .addEdge("draft", "review")
                // 业务循环条件：达标结束；轮数用尽也结束（防模型死循环烧钱）
                .addConditionalEdge("review", state -> {
                    int round = (Integer) state.getOrDefault("round", 0);
                    if ((Integer) state.get("score") >= PASS_SCORE || round >= MAX_REVIEW_ROUNDS) {
                        return StateGraph.END;
                    }
                    return "revise";
                })
                .addEdge("revise", "review");

        CompiledGraph.Execution execution = loopGraph.compile(new CheckpointStore(), 24).run(
                new LinkedHashMap<>(Map.of("topic", topic)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topic", topic);
        result.put("finalDraft", execution.state().get("draft"));
        result.put("finalScore", execution.state().get("score"));
        result.put("rounds", (Integer) execution.state().getOrDefault("round", 0));
        result.put("trace", execution.trace());
        result.put("note", "这个循环的每个环节都是业务代码显式定义的（对照 lesson16 的框架黑盒循环）；"
                + "轮数上限与引擎步数上限双重兜底。");
        return result;
    }

    /** 评审输出可能带杂质，宽松解析 1-10 分；解析失败按满分处理（宁缺毋滥地放行） */
    static int parseScore(String raw) {
        try {
            int score = Integer.parseInt(raw.replaceAll("[^0-9]", "").trim());
            return Math.max(1, Math.min(10, score));
        } catch (RuntimeException e) {
            return 10;
        }
    }

    // ---------- 2) 图 + 3) 人在环中：退款审批工作流 ----------

    @GetMapping("/lesson21/graph/run")
    public Map<String, Object> run(@RequestParam(defaultValue = "订单A1001要退款") String q) {
        CompiledGraph.Execution execution = approvalWorkflow.run(RefundApprovalWorkflow.initialState(q));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", execution.status());
        result.put("executionId", execution.executionId());
        result.put("trace", execution.trace());
        result.put("state", execution.state());
        if (CompiledGraph.Execution.SUSPENDED.equals(execution.status())) {
            result.put("pendingPrompt", execution.pendingPrompt());
            result.put("next", "POST /lesson21/graph/approve/" + execution.executionId()
                    + "?approved=true|false&comment=审批意见");
        }
        return result;
    }

    /** 人工审批（另一个请求、可能另一个人）：把决定合并进状态并恢复执行 */
    @PostMapping("/lesson21/graph/approve/{executionId}")
    public Map<String, Object> approve(
            @PathVariable String executionId,
            @RequestParam(defaultValue = "true") boolean approved,
            @RequestParam(required = false) String comment) {

        CompiledGraph.Execution execution = approvalWorkflow.resume(executionId,
                Map.of(RefundApprovalWorkflow.APPROVED, approved,
                        RefundApprovalWorkflow.DECISION_COMMENT, comment == null ? "" : comment));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", execution.status());
        result.put("trace", execution.trace());
        result.put("state", execution.state());
        return result;
    }

    /** 待审批列表（生产上这就是审批中心的待办数据源） */
    @GetMapping("/lesson21/graph/pending")
    public Map<String, Object> pending() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", approvalWorkflow.pending().stream()
                .map(s -> Map.of(
                        "executionId", s.executionId(),
                        "suspendedAt", s.currentNode(),
                        "pendingPrompt", s.pendingPrompt(),
                        "trace", s.trace()))
                .toList());
        return result;
    }
}
