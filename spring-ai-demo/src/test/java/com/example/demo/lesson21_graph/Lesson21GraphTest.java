package com.example.demo.lesson21_graph;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 21 课单元测试：不调用任何模型（图节点全是假函数 + 审批工作流全程纯 Java）。
 * 验证图引擎的顺序/条件路由/循环保护，以及人在环中的挂起-恢复闭环。
 */
class Lesson21GraphTest {

    private static Map<String, Object> state(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---------- 引擎：顺序与条件路由 ----------

    @Test
    void linearGraphRunsNodesInOrder() {
        CompiledGraph graph = new StateGraph()
                .addNode("a", s -> {
                    s.put("x", 1);
                    return s;
                })
                .addNode("b", s -> {
                    s.put("x", ((Integer) s.get("x")) + 1);
                    return s;
                })
                .entry("a")
                .addEdge("a", "b")
                .addEdge("b", StateGraph.END)
                .compile(new CheckpointStore());

        CompiledGraph.Execution execution = graph.run(state());
        assertThat(execution.status()).isEqualTo(CompiledGraph.Execution.DONE);
        assertThat(execution.trace()).containsExactly("a", "b");
        assertThat(execution.state().get("x")).isEqualTo(2);
    }

    @Test
    void conditionalEdgeRoutesByState() {
        CompiledGraph graph = new StateGraph()
                .addNode("classify", s -> {
                    s.put("intent", String.valueOf(s.get("q")).contains("退款") ? "refund" : "faq");
                    return s;
                })
                .addNode("refund", s -> {
                    s.put("out", "退款分支");
                    return s;
                })
                .addNode("faq", s -> {
                    s.put("out", "问答分支");
                    return s;
                })
                .entry("classify")
                .addConditionalEdge("classify", s -> "refund".equals(s.get("intent")) ? "refund" : "faq")
                .addEdge("refund", StateGraph.END)
                .addEdge("faq", StateGraph.END)
                .compile(new CheckpointStore());

        assertThat(graph.run(state("q", "我要退款")).state().get("out")).isEqualTo("退款分支");
        assertThat(graph.run(state("q", "怎么开发票")).state().get("out")).isEqualTo("问答分支");
    }

    @Test
    void engineHardCapStopsInfiniteLoop() {
        // 自环（A 永远回到 A）：路由条件永远到不了 END——图编排最常见的写错方式
        CompiledGraph graph = new StateGraph()
                .addNode("a", s -> s)
                .entry("a")
                .addEdge("a", "a")
                .compile(new CheckpointStore(), 10);

        assertThatThrownBy(() -> graph.run(state()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("上限");
    }

    // ---------- 人在环中：审批工作流（真实图，纯 Java 节点） ----------

    @Test
    void refundRequestSuspendsForHumanApproval() {
        CompiledGraph graph = RefundApprovalWorkflow.graph().compile(new CheckpointStore());

        CompiledGraph.Execution execution = graph.run(RefundApprovalWorkflow.initialState("订单A1001要退款"));

        assertThat(execution.status()).isEqualTo(CompiledGraph.Execution.SUSPENDED);
        assertThat(execution.pendingPrompt()).contains("人工审批");
        assertThat(execution.state().get(RefundApprovalWorkflow.ORDER_INFO)).isNotNull();
        // 挂起位置就是审批节点，refundGate 已执行过一次（抛挂起前计入 trace）
        assertThat(execution.trace()).containsExactly("classify", "prepare", "refundGate");
    }

    @Test
    void approvalResumesAndExecutesRefund() {
        CompiledGraph graph = RefundApprovalWorkflow.graph().compile(new CheckpointStore());
        CompiledGraph.Execution suspended = graph.run(RefundApprovalWorkflow.initialState("订单A1002要退款"));

        CompiledGraph.Execution done = graph.resume(suspended.executionId(),
                Map.of(RefundApprovalWorkflow.APPROVED, true, RefundApprovalWorkflow.DECISION_COMMENT, "质量问题属实"));

        assertThat(done.status()).isEqualTo(CompiledGraph.Execution.DONE);
        assertThat(String.valueOf(done.state().get(RefundApprovalWorkflow.RESULT)))
                .contains("退款已执行").contains("质量问题属实");
        // 完整路径：挂起前的 refundGate + 恢复后重入的 refundGate + execute
        assertThat(done.trace()).containsExactly("classify", "prepare", "refundGate", "refundGate", "execute");
    }

    @Test
    void rejectionRoutesToManualTicket() {
        CompiledGraph graph = RefundApprovalWorkflow.graph().compile(new CheckpointStore());
        CompiledGraph.Execution suspended = graph.run(RefundApprovalWorkflow.initialState("订单A1001要退款"));

        CompiledGraph.Execution done = graph.resume(suspended.executionId(),
                Map.of(RefundApprovalWorkflow.APPROVED, false));

        assertThat(done.status()).isEqualTo(CompiledGraph.Execution.DONE);
        assertThat(String.valueOf(done.state().get(RefundApprovalWorkflow.RESULT)))
                .contains("拒绝").contains("人工工单");
        assertThat(done.trace()).last().isEqualTo("manual");
    }

    @Test
    void snapshotIsSingleConsumption() {
        CompiledGraph graph = RefundApprovalWorkflow.graph().compile(new CheckpointStore());
        CompiledGraph.Execution suspended = graph.run(RefundApprovalWorkflow.initialState("订单A1001要退款"));

        assertThat(graph.pending()).hasSize(1);
        graph.resume(suspended.executionId(), Map.of(RefundApprovalWorkflow.APPROVED, true));
        // 取即删：一次挂起只接受一次人工输入，重复 resume 报错而不是重复执行付款
        assertThat(graph.pending()).isEmpty();
        assertThatThrownBy(() -> graph.resume(suspended.executionId(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或已恢复");
    }

    @Test
    void faqAndNoOrderBranchesSkipApproval() {
        CompiledGraph graph = RefundApprovalWorkflow.graph().compile(new CheckpointStore());

        CompiledGraph.Execution faq = graph.run(RefundApprovalWorkflow.initialState("发票怎么开"));
        assertThat(faq.status()).isEqualTo(CompiledGraph.Execution.DONE);
        assertThat(faq.trace()).containsExactly("classify", "faq");

        // 提到退款但没带订单号：走不到审批门，直接提示补充信息
        CompiledGraph.Execution noOrder = graph.run(RefundApprovalWorkflow.initialState("我要退款"));
        assertThat(noOrder.status()).isEqualTo(CompiledGraph.Execution.DONE);
        assertThat(String.valueOf(noOrder.state().get(RefundApprovalWorkflow.RESULT))).contains("订单号");
    }

    // ---------- 评审打分解析 ----------

    @Test
    void parseScoreToleratesNoisyOutput() {
        assertThat(Lesson21Controller.parseScore("8")).isEqualTo(8);
        assertThat(Lesson21Controller.parseScore("评分：7 分")).isEqualTo(7);
        assertThat(Lesson21Controller.parseScore("我觉得写得不错，给 9 分")).isEqualTo(9);
        // 解析不出数字按满分放行：评审失效时宁可放行也不能死循环烧钱
        assertThat(Lesson21Controller.parseScore("非常好")).isEqualTo(10);
    }
}
