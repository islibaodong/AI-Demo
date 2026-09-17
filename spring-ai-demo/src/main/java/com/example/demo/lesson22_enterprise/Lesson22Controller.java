package com.example.demo.lesson22_enterprise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.lesson14_observability.ModelPricing;
import com.example.demo.lesson18_evals.EvalCase;
import com.example.demo.lesson18_evals.EvalRunner;
import com.example.demo.lesson20_permissions.Lesson20Controller;
import com.example.demo.lesson20_permissions.PermissionFilteredRetriever;
import com.example.demo.lesson20_permissions.UserPrincipal;
import com.example.demo.lesson21_graph.CheckpointStore;
import com.example.demo.lesson21_graph.CompiledGraph;
import com.example.demo.lesson21_graph.StateGraph;

/**
 * 第 22 课：企业级 Agent 开发（整合课）—— 把权限、审批、治理、观测、评估装进一张图。
 *
 * <p>lesson19 把<b>功能</b>串成了客服系统；本课把<b>治理域</b>串成企业报销助手，
 * 一条链路演示企业级 Agent 的完整闭环：</p>
 *
 * <pre>
 *   GET /lesson22/agent?user=bob&q=帮EX5002发起付款
 *     ├─ 1 身份解析        user=bob → UserPrincipal（lesson20）
 *     ├─ 2 权限过滤检索     机密文档在检索层被滤掉，不进提示词（lesson20）
 *     ├─ 3 治理 Agent      模型调工具：审计轨迹 + 工具内 RBAC/行级闸门（lesson20）
 *     │                    requestPayment 不直接付款，经 hooks 通道"举手报告"
 *     ├─ 4 人在环中        riskGate 节点挂起流程，返回 executionId（lesson21）
 *     │   POST /lesson22/approve/{id}?approved=true   人工批准 → executePayment
 *     └─ 5 观测            tokens + estimatedCostUsd（lesson14）
 *   GET /lesson22/evals   探针回归：行级拒绝 / RBAC 拒绝 / 机密不可见 / 冒烟（lesson18）
 * </pre>
 *
 * <p><b>为什么"付款"要挂起而不是 lesson16 那样拦截改道？</b>高危写操作（付钱/删数据）
 * 的正确姿势是<b>硬管控</b>：流程冻结、等真人决定、决定驱动分支——模型最多是"发起人"，
 * 决定权在人。lesson16 的审批门适合低危场景（模型自主改道不炸链路）。</p>
 *
 * <p>试试：</p>
 * <pre>
 * curl "localhost:8080/lesson22/agent?user=bob&q=帮我的报销单EX5002发起付款"
 *   # → status=suspended + executionId（bob 有权限发起，但付款必须等审批）
 * curl -X POST "localhost:8080/lesson22/approve/&lt;executionId&gt;?approved=true&comment=属实"
 *   # → result=付款已执行
 * curl "localhost:8080/lesson22/agent?user=carol&q=查一下EX5002"       # 行级拒绝
 * curl "localhost:8080/lesson22/agent?user=alice&q=公司的差旅标准是什么"  # 权限内问答
 * curl "localhost:8080/lesson22/evals"                                 # 4 条探针回归
 * </pre>
 */
@RestController
public class Lesson22Controller {

    private final ChatClient client;
    /** 审批挂起跨 HTTP 请求存续：checkpoint 是企业审批流的"待办数据库" */
    private final CheckpointStore checkpoints = new CheckpointStore();
    private final CompiledGraph workflow;

    public Lesson22Controller(ChatModel chatModel) {
        this.client = ChatClient.builder(chatModel).build();
        this.workflow = paymentGraph(this::agentNode).compile(checkpoints);
    }

    // ---------- 主入口：一条链路串起治理域 ----------

    @GetMapping("/lesson22/agent")
    public Map<String, Object> agent(
            @RequestParam(defaultValue = "bob") String user,
            @RequestParam(defaultValue = "帮我查一下报销单 EX5002") String q) {

        UserPrincipal principal = UserPrincipal.resolve(user);
        Map<String, Object> result = new LinkedHashMap<>();
        if (principal == null) {
            result.put("error", "未知用户：%s（可用：%s）".formatted(user,
                    UserPrincipal.all().stream().map(UserPrincipal::userId).toList()));
            return result;
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("q", q);
        state.put("user", principal);
        CompiledGraph.Execution execution = workflow.run(state);

        result.put("status", execution.status());
        result.put("identity", Map.of("userId", principal.userId(), "role", principal.role().name()));
        result.put("answer", execution.state().get("answer"));
        result.put("citations", execution.state().getOrDefault("citations", List.of()));
        result.put("toolCalls", execution.state().getOrDefault("audit", List.of()));
        result.put("tokens", execution.state().get("tokens"));
        result.put("estimatedCostUsd", execution.state().get("estimatedCostUsd"));
        result.put("trace", execution.trace());
        if (CompiledGraph.Execution.SUSPENDED.equals(execution.status())) {
            result.put("pendingPrompt", execution.pendingPrompt());
            result.put("next", "POST /lesson22/approve/" + execution.executionId()
                    + "?approved=true|false&comment=审批意见");
        }
        return result;
    }

    // ---------- 人工审批：恢复挂起的付款流程 ----------

    @PostMapping("/lesson22/approve/{executionId}")
    public Map<String, Object> approve(
            @PathVariable String executionId,
            @RequestParam(defaultValue = "true") boolean approved,
            @RequestParam(required = false) String comment) {
        CompiledGraph.Execution execution = workflow.resume(executionId,
                Map.of("approved", approved, "decisionComment", comment == null ? "" : comment));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", execution.status());
        result.put("result", execution.state().get("result"));
        result.put("trace", execution.trace());
        return result;
    }

    /** 待审批列表（企业审批中心的待办数据源） */
    @GetMapping("/lesson22/pending")
    public Map<String, Object> pending() {
        return Map.of("pending", workflow.pending().stream()
                .map(s -> Map.of("executionId", s.executionId(), "pendingPrompt", s.pendingPrompt()))
                .toList());
    }

    // ---------- 工作流图：retrieve → agent → riskGate ─┬─ approved → executePayment → END
    //                                                    ├─ rejected → manual → END
    //                                                    └─ 无付款动作 → END

    /**
     * 图工厂：agent 节点由调用方注入（生产=模型 Agent；测试=假节点，
     * 这也是本课离线可测的关键——图结构与模型解耦）。
     */
    public static StateGraph paymentGraph(StateGraph.Node agentNode) {
        return new StateGraph()
                .addNode("retrieve", state -> {
                    UserPrincipal user = (UserPrincipal) state.get("user");
                    String q = String.valueOf(state.get("q"));
                    List<Document> hits = PermissionFilteredRetriever.searchFor(
                            user, q, Lesson20Controller.kbCorpus(), 3);
                    List<Map<String, Object>> citations = new ArrayList<>();
                    StringBuilder context = new StringBuilder();
                    for (int i = 0; i < hits.size(); i++) {
                        Document d = hits.get(i);
                        context.append("[").append(i + 1).append("] ").append(d.getText()).append("\n");
                        citations.add(Map.of(
                                "source", d.getMetadata().get("source"),
                                "visibility", d.getMetadata().get("visibility")));
                    }
                    state.put("context", context.toString());
                    state.put("citations", citations);
                    return state;
                })
                .addNode("agent", agentNode)
                // 人在环中的审批门：有付款动作且没人批过 → 挂起（重入时决定已在状态里）
                .addNode("riskGate", state -> {
                    if (state.get("pendingPayment") != null && state.get("approved") == null) {
                        throw new StateGraph.HumanInputRequired(
                                "付款申请需人工审批：报销单 %s（%s 元）"
                                        .formatted(state.get("pendingPayment"), state.get("pendingAmount")));
                    }
                    return state;
                })
                .addNode("executePayment", state -> {
                    state.put("result", "付款已执行：报销单 %s，金额 %s 元（审批意见：%s）"
                            .formatted(state.get("pendingPayment"), state.get("pendingAmount"),
                                    state.getOrDefault("decisionComment", "无")));
                    return state;
                })
                .addNode("manual", state -> {
                    state.put("result", "付款申请已拒绝，报销单 %s 退回申请人（审批意见：%s）"
                            .formatted(state.get("pendingPayment"),
                                    state.getOrDefault("decisionComment", "无")));
                    return state;
                })
                .entry("retrieve")
                .addEdge("retrieve", "agent")
                .addEdge("agent", "riskGate")
                .addConditionalEdge("riskGate", state -> {
                    if (state.get("pendingPayment") == null) {
                        return StateGraph.END;                       // 纯问答，没触发付款
                    }
                    return Boolean.TRUE.equals(state.get("approved"))
                            ? "executePayment"                        // 人工批准 → 执行
                            : "manual";                               // 人工拒绝 → 退回
                })
                .addEdge("executePayment", StateGraph.END)
                .addEdge("manual", StateGraph.END);
    }

    // ---------- agent 节点：模型 + 带权限工具 + hooks 通道 ----------

    /**
     * 模型 Agent 节点：权限过滤后的资料进系统提示，工具经审计装饰器挂上，
     * 身份与 hooks 走 ToolContext 旁路。requestPayment 被调用时经 hooks"举手"，
     * 本节点把举手结果合并进图状态，交给下游 riskGate 挂起。
     */
    private Map<String, Object> agentNode(Map<String, Object> state) {
        UserPrincipal user = (UserPrincipal) state.get("user");
        String q = String.valueOf(state.get("q"));
        List<String> audit = new ArrayList<>();
        Map<String, Object> hooks = new HashMap<>();

        ToolCallback[] tools = Arrays.stream(MethodToolCallbackProvider.builder()
                .toolObjects(new ExpenseTools())
                .build()
                .getToolCallbacks())
                .map(t -> (ToolCallback) new Lesson20Controller.AuditedTool(t, audit))
                .toArray(ToolCallback[]::new);

        String system = """
                你是企业报销助手。当前用户的身份经工具上下文传入，你不需要也不能替用户声明身份。
                可依据下面的资料回答报销制度问题；资料里没有的明确说没有。
                涉及付款操作必须通过工具发起，返回"权限不足"或"已提交审批"时如实转告用户。
                资料：
                %s""".formatted(state.get("context"));

        // 只调 chatResponse() 一个终端操作（content() 与 chatResponse() 各触发一次完整调用）
        ChatResponse response = client.prompt()
                .system(system)
                .user(q)
                .toolCallbacks(tools)
                .toolContext(Map.of("user", user, "hooks", hooks))
                .call()
                .chatResponse();

        state.put("answer", response.getResult().getOutput().getText());
        state.put("audit", List.copyOf(audit));
        var usage = response.getMetadata().getUsage();
        ModelPricing.Cost cost = ModelPricing.of(response.getMetadata().getModel()).estimate(usage);
        state.put("tokens", usage.getTotalTokens());
        state.put("estimatedCostUsd", cost.totalCost());

        // hooks 通道：工具的"举手报告"合并进图状态（模型输出不可信，代码写入才可信）
        if (hooks.containsKey("pendingPayment")) {
            state.put("pendingPayment", hooks.get("pendingPayment"));
            state.put("pendingAmount", hooks.get("pendingAmount"));
        }
        return state;
    }

    // ---------- 探针回归：权限与过滤是安全属性，必须可回归 ----------

    /**
     * 权限探针跑批（lesson18 的 EvalRunner）。四条探针全部走<b>纯 Java 路径</b>
     * （工具直调 + 检索直调），不调模型——权限是安全属性，回归必须确定性、可离线。
     * SUT 输入格式：{@code "<工具名> <单号> as <用户>"} 或 {@code "filter <关键词> as <用户>"}。
     */
    @GetMapping("/lesson22/evals")
    public Map<String, Object> evals() {
        UnaryOperator<String> sut = input -> {
            String[] parts = input.trim().split("\\s+");
            return switch (parts[0]) {
                // 行级：carol 查 bob 的报销单 → 拒绝
                case "queryExpense" -> callTool("queryExpense",
                        "{\"expenseId\": \"%s\"}".formatted(parts[1]), parts[3]);
                // RBAC：carol（普通员工）发起付款 → 拒绝
                case "requestPayment" -> callTool("requestPayment",
                        "{\"expenseId\": \"%s\"}".formatted(parts[1]), parts[3]);
                // 检索层：carol 的资料里不应出现机密内容
                case "filter" -> {
                    List<Document> hits = PermissionFilteredRetriever.searchFor(
                            UserPrincipal.resolve(parts[3]),
                            input.substring(parts[0].length(), input.lastIndexOf("as")).trim(),
                            Lesson20Controller.kbCorpus(), 3);
                    yield hits.stream().map(Document::getText).reduce("", (a, b) -> a + "\n" + b);
                }
                default -> throw new IllegalArgumentException("未知探针类型：" + parts[0]);
            };
        };

        List<EvalCase> cases = List.of(
                EvalCase.of("行级权限：员工查他人报销单被拒", "permissions",
                        "queryExpense EX5002 as carol", new EvalCase.Contains("权限不足")),
                EvalCase.of("RBAC：普通员工发起付款被拒", "permissions",
                        "requestPayment EX5001 as carol", new EvalCase.Contains("权限不足")),
                EvalCase.of("机密不可见：员工检索结果不含机密口径", "data-permission",
                        "filter 高管差旅实报实销 as carol", new EvalCase.NotContains("实报实销")),
                EvalCase.of("冒烟：本人查单正常返回", "smoke",
                        "queryExpense EX5001 as carol", new EvalCase.NonBlank()));

        EvalRunner.Summary summary = new EvalRunner().run(cases, sut);
        return Map.of(
                "total", summary.total(),
                "passed", summary.passed(),
                "passRate", summary.passRate(),
                "results", summary.results());
    }

    /** 按工具名取回调并带身份执行（探针 SUT 的工具直调路径） */
    private static String callTool(String toolName, String jsonInput, String userId) {
        UserPrincipal user = UserPrincipal.resolve(userId);
        ToolCallback[] tools = MethodToolCallbackProvider.builder()
                .toolObjects(new ExpenseTools())
                .build()
                .getToolCallbacks();
        return Arrays.stream(tools)
                .filter(t -> t.getToolDefinition().name().equals(toolName))
                .findFirst()
                .orElseThrow()
                .call(jsonInput, new ToolContext(Map.of("user", user)));
    }
}
