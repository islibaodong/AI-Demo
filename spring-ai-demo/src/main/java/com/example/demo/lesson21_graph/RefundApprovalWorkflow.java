package com.example.demo.lesson21_graph;

import java.util.List;
import java.util.Map;

/**
 * 第 21 课：审批工作流示例 —— 退款申请挂起等人工，审批结果驱动分支。
 *
 * <p>刻意<b>全程不调用模型</b>（分类用关键词、FAQ 用固定话术）：把"人在环中"的机制
 * 讲清楚只需要图引擎，不需要模型——这也是离线单测能覆盖完整审批闭环的原因。
 * 生产里 classify/faq 节点换成模型调用即可，图结构不变。</p>
 *
 * <p>图结构：</p>
 * <pre>
 *   classify ──退款?──▶ prepare ──▶ refundGate(审批节点) ──通过──▶ execute ──▶ END
 *      │                                    │
 *      └──其他──▶ faq ──▶ END               └──拒绝──▶ manual ──▶ END
 * </pre>
 *
 * <p><b>人在环中的关键设计</b>：{@code refundGate} 第一次执行时状态里没有
 * {@code approved} → 抛 {@link StateGraph.HumanInputRequired} 挂起；
 * 人工审批（另一个 HTTP 请求）把 {@code approved=true/false} 合并进状态，
 * resume 后<b>同一个节点</b>第二次执行，这次读到了决定 → 条件边把流程引向
 * execute 或 manual。节点重入读状态分支，这就是 LangGraph interrupt/resume 的全部秘密。</p>
 *
 * <p><b>与 lesson16 审批门的区别</b>：lesson16 的审批门是"拦截并引导模型改道"
 * （模型仍在场，自主决定替代方案）；本课是"流程挂起等真人决定"
 * （模型可以完全不在场）——前者是软治理，后者是硬管控，高危操作（退款/转账/删除）
 * 生产上用后者。</p>
 */
public final class RefundApprovalWorkflow {

    private RefundApprovalWorkflow() {
    }

    /** 演示订单数据（简单归属，行级判断只做"是否存在"） */
    private static final Map<String, String> ORDERS = Map.of(
            "A1001", "机械键盘 399 元",
            "A1002", "降噪耳机 1299 元",
            "A1003", "办公椅 2999 元");

    /** 状态 key 常量（state 是 Map，用常量避免散落的魔法字符串） */
    public static final String Q = "q";
    public static final String INTENT = "intent";
    public static final String ORDER_INFO = "orderInfo";
    public static final String APPROVED = "approved";
    public static final String DECISION_COMMENT = "decisionComment";
    public static final String RESULT = "result";

    /** 组装图（每次调用返回新图；节点都是无状态 lambda，单实例并发安全） */
    public static StateGraph graph() {
        return new StateGraph()
                .addNode("classify", state -> {
                    String q = String.valueOf(state.get(Q));
                    state.put(INTENT, (q.contains("退款") || q.contains("退货")) ? "refund" : "faq");
                    return state;
                })
                .addNode("faq", state -> {
                    state.put(RESULT, "常见问题：退款原路退回，3-5 个工作日到账（参考资料口径）。");
                    return state;
                })
                .addNode("prepare", state -> {
                    String q = String.valueOf(state.get(Q));
                    String info = ORDERS.entrySet().stream()
                            .filter(e -> q.contains(e.getKey()))
                            .map(Map.Entry::getValue)
                            .findFirst()
                            .orElse(null);
                    if (info == null) {
                        state.put(RESULT, "未在请求中识别到订单号，请补充订单号后重试。");
                        state.put(INTENT, "noorder");
                    } else {
                        state.put(ORDER_INFO, info);
                    }
                    return state;
                })
                // 审批节点：没有人工决定 → 挂起；有人工决定 → 原样放行（路由在条件边做）
                .addNode("refundGate", state -> {
                    if (state.get(APPROVED) == null) {
                        throw new StateGraph.HumanInputRequired(
                                "订单 %s 的退款申请需人工审批（演示：%s）"
                                        .formatted(state.get(ORDER_INFO), state.get(Q)));
                    }
                    return state;
                })
                .addNode("execute", state -> {
                    state.put(RESULT, "退款已执行：%s，审批意见：%s"
                            .formatted(state.get(ORDER_INFO), state.getOrDefault(DECISION_COMMENT, "无")));
                    return state;
                })
                .addNode("manual", state -> {
                    state.put(RESULT, "审批拒绝，已创建人工工单跟进（审批意见：%s）"
                            .formatted(state.getOrDefault(DECISION_COMMENT, "无")));
                    return state;
                })
                .entry("classify")
                .addConditionalEdge("classify", state ->
                        "refund".equals(state.get(INTENT)) ? "prepare" : "faq")
                .addEdge("faq", StateGraph.END)
                // prepare 可能把 intent 改写为 noorder（识别不到订单号），直接结束
                .addConditionalEdge("prepare", state ->
                        "noorder".equals(state.get(INTENT)) ? StateGraph.END : "refundGate")
                // 审批后分支：重入的 refundGate 放行后按决定路由
                .addConditionalEdge("refundGate", state ->
                        Boolean.TRUE.equals(state.get(APPROVED)) ? "execute" : "manual")
                .addEdge("execute", StateGraph.END)
                .addEdge("manual", StateGraph.END);
    }

    /** 初始状态工厂 */
    public static Map<String, Object> initialState(String q) {
        return new java.util.LinkedHashMap<>(Map.of(Q, q == null ? "" : q));
    }

    /** FAQ 分支的固定口径（列出来供注释/测试对照） */
    public static List<String> faqTopics() {
        return List.of("退款", "退货");
    }
}
