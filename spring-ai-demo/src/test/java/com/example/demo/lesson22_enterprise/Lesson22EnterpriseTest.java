package com.example.demo.lesson22_enterprise;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import com.example.demo.lesson20_permissions.Lesson20Controller;
import com.example.demo.lesson20_permissions.PermissionFilteredRetriever;
import com.example.demo.lesson20_permissions.UserPrincipal;
import com.example.demo.lesson21_graph.CheckpointStore;
import com.example.demo.lesson21_graph.CompiledGraph;
import com.example.demo.lesson21_graph.StateGraph;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 22 课单元测试：不调用任何模型。Agent 节点用假函数注入（图结构与模型解耦），
 * 工具与检索走真实通路。验证：审批挂起/恢复闭环、工具 RBAC/行级闸门、
 * hooks 通道、检索层机密过滤、以及权限探针跑批全绿。
 */
class Lesson22EnterpriseTest {

    /** Mockito mock 的 ChatModel：ChatClient 内部会 mutate options，必须 stub getOptions() */
    private static ChatModel mockChatModel() {
        ChatModel model = Mockito.mock(ChatModel.class);
        Mockito.when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        return model;
    }

    // ---------- 图闭环（agent 节点 = 假函数，模拟 hooks 举手） ----------

    /** 假 agent 节点：含关键词"付款/EX"时模拟 requestPayment 的举手报告 */
    private static StateGraph.Node fakeAgentNode() {
        return state -> {
            String q = String.valueOf(state.get("q"));
            if (q.contains("付款") || q.contains("EX5")) {
                state.put("pendingPayment", "EX5002");
                state.put("pendingAmount", "9800.0");
            }
            state.put("answer", "假回答");
            return state;
        };
    }

    @Test
    void paymentFlowSuspendsThenExecutesAfterApproval() {
        CompiledGraph graph = Lesson22Controller.paymentGraph(fakeAgentNode()).compile(new CheckpointStore());

        CompiledGraph.Execution suspended = graph.run(stateOf("帮EX5002发起付款"));
        assertThat(suspended.status()).isEqualTo(CompiledGraph.Execution.SUSPENDED);
        assertThat(suspended.pendingPrompt()).contains("EX5002").contains("人工审批");

        CompiledGraph.Execution done = graph.resume(suspended.executionId(),
                Map.of("approved", true, "decisionComment", "属实"));
        assertThat(done.status()).isEqualTo(CompiledGraph.Execution.DONE);
        assertThat(String.valueOf(done.state().get("result"))).contains("付款已执行").contains("EX5002");
        assertThat(done.trace()).contains("executePayment");
    }

    @Test
    void rejectedPaymentGoesBackToApplicant() {
        CompiledGraph graph = Lesson22Controller.paymentGraph(fakeAgentNode()).compile(new CheckpointStore());
        CompiledGraph.Execution suspended = graph.run(stateOf("帮EX5002发起付款"));

        CompiledGraph.Execution done = graph.resume(suspended.executionId(), Map.of("approved", false));
        assertThat(String.valueOf(done.state().get("result"))).contains("拒绝").contains("退回");
    }

    @Test
    void plainQuestionEndsWithoutApproval() {
        CompiledGraph graph = Lesson22Controller.paymentGraph(fakeAgentNode()).compile(new CheckpointStore());

        CompiledGraph.Execution done = graph.run(stateOf("报销标准是什么"));
        assertThat(done.status()).isEqualTo(CompiledGraph.Execution.DONE);
        // 无付款动作：riskGate 放行直达 END，没有 executePayment/manual
        assertThat(done.trace()).containsExactly("retrieve", "agent", "riskGate");
    }

    // ---------- 真实工具：RBAC + 行级 + hooks 通道 ----------

    private static String callTool(String name, String json, UserPrincipal user, Map<String, Object> extra) {
        Map<String, Object> ctx = new HashMap<>();
        if (user != null) {
            ctx.put("user", user);
        }
        if (extra != null) {
            ctx.putAll(extra);
        }
        ToolCallback callback = java.util.Arrays.stream(MethodToolCallbackProvider.builder()
                .toolObjects(new ExpenseTools())
                .build()
                .getToolCallbacks())
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
        return callback.call(json, new ToolContext(ctx));
    }

    @Test
    void rowLevelDeniesReadingOthersExpense() {
        String denied = callTool("queryExpense", "{\"expenseId\": \"EX5002\"}",
                UserPrincipal.resolve("carol"), null);
        assertThat(denied).contains("权限不足").doesNotContain("9800");
    }

    @Test
    void employeeCannotRequestPayment() {
        String denied = callTool("requestPayment", "{\"expenseId\": \"EX5001\"}",
                UserPrincipal.resolve("carol"), null);
        assertThat(denied).contains("权限不足");
    }

    @Test
    void supportCanRequestPaymentWhichRaisesHandInsteadOfPaying() {
        // requestPayment 不直接付款：RBAC/行级通过后经 hooks 举手，等图引擎的审批门
        Map<String, Object> hooks = new HashMap<>();
        String ok = callTool("requestPayment", "{\"expenseId\": \"EX5002\"}",
                UserPrincipal.resolve("bob"), Map.of("hooks", hooks));
        assertThat(ok).contains("已提交").doesNotContain("已执行");
        assertThat(hooks).containsEntry("pendingPayment", "EX5002");
        assertThat(hooks).containsEntry("pendingAmount", 9800.0);
    }

    @Test
    void adminReadsAnyExpense() {
        String ok = callTool("queryExpense", "{\"expenseId\": \"EX5001\"}",
                UserPrincipal.resolve("alice"), null);
        assertThat(ok).contains("350").contains("carol");
    }

    // ---------- 检索层机密过滤 ----------

    @Test
    void confidentialStaysOutOfEmployeeRetrieval() {
        List<Document> hits = PermissionFilteredRetriever.searchFor(
                UserPrincipal.resolve("carol"), "高管差旅实报实销",
                Lesson20Controller.kbCorpus(), 3);
        String joined = hits.stream().map(Document::getText).reduce("", (a, b) -> a + b);
        assertThat(joined).doesNotContain("实报实销");
    }

    // ---------- 权限探针跑批（真实 EvalRunner，纯 Java SUT） ----------

    @Test
    void permissionProbesAllPass() {
        Lesson22Controller controller = new Lesson22Controller(mockChatModel());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) controller.evals();

        assertThat(summary.get("total")).isEqualTo(4);
        assertThat(summary.get("passed")).isEqualTo(4);
    }

    private static Map<String, Object> stateOf(String q) {
        Map<String, Object> state = new HashMap<>();
        state.put("q", q);
        state.put("user", UserPrincipal.resolve("bob"));
        return state;
    }
}
