package com.example.demo.lesson22_enterprise;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.example.demo.lesson20_permissions.UserPrincipal;

/**
 * 第 22 课：企业报销工具 —— lesson20 的权限闸门 + lesson21 的"高危动作触发审批"。
 *
 * <p>两个工具的权限语义不同，正好覆盖企业场景的两类动作：</p>
 * <ul>
 *   <li>{@code queryExpense}：<b>读</b>，行级权限——只能看自己的报销单（管理员例外）；
 *       这类动作风险低，放行即可；</li>
 *   <li>{@code requestPayment}：<b>写</b>（对外付款），工具级 RBAC（客服以上）+
 *       行级 + <b>不直接执行</b>——它只做两件事：校验通过后把待付款信息写进
 *       {@code hooks}（ToolContext 里业务代码放的暗号通道），并返回"已提交审批"。
 *       真正的付款由图引擎的审批门挂起、人工批准后由专门的节点执行
 *       （见 Lesson22Controller 的 riskGate / executePayment）。</li>
 * </ul>
 *
 * <p><b>hooks 通道</b>是本课的一个小技巧：工具（被框架循环调用）与图节点（调用方）
 * 之间通过 ToolContext 里共享的 Map 通信——工具改不了图状态，但可以"举手报告"，
 * 节点在模型返回后读取。比让模型在参数里声明"我触发了审批"可靠得多：
 * 模型输出是可诱导的，代码写入是可信的。</p>
 */
public class ExpenseTools {

    /** 一笔报销单：归属人是行级权限的判定依据 */
    public record Expense(String expenseId, String ownerUserId, double amount, String description, String status) {
    }

    static final List<Expense> EXPENSES = List.of(
            new Expense("EX5001", "carol", 350.0, "研发部市内差旅打车费", "待审批"),
            new Expense("EX5002", "bob", 9800.0, "客服部外地驻场差旅（机票+酒店）", "待审批"),
            new Expense("EX5003", "alice", 66.0, "管理部培训教材费", "待审批"));

    static Expense find(String expenseId) {
        return EXPENSES.stream().filter(e -> e.expenseId().equalsIgnoreCase(expenseId)).findFirst().orElse(null);
    }

    // ---------- 工具 1：查报销单（读：行级权限） ----------

    @Tool(description = "按单号查询报销单详情。只能查询本人的报销单，管理员可查全部。")
    public String queryExpense(@ToolParam(description = "报销单号，如 EX5001") String expenseId,
            ToolContext context) {
        UserPrincipal user = UserPrincipal.fromContext(context.getContext());
        if (user == null) {
            return "未登录：需要用户身份才能查询。";
        }
        Expense expense = find(expenseId);
        if (expense == null) {
            return "报销单 %s 不存在。".formatted(expenseId);
        }
        if (!canAccessRow(user, expense)) {
            return "权限不足：报销单 %s 归属 %s，你只能查询本人的报销单。".formatted(expenseId, expense.ownerUserId());
        }
        return "%s：%s，金额 %.2f 元，状态：%s（申请人：%s）"
                .formatted(expense.expenseId(), expense.description(), expense.amount(),
                        expense.status(), expense.ownerUserId());
    }

    // ---------- 工具 2：申请付款（写：RBAC + 行级 + 触发审批，不直接执行） ----------

    @Tool(description = "对指定报销单发起付款申请。仅客服和管理员可发起；发起后进入人工审批流程，本工具不会直接付款。")
    public String requestPayment(@ToolParam(description = "报销单号") String expenseId, ToolContext context) {
        UserPrincipal user = UserPrincipal.fromContext(context.getContext());
        if (user == null) {
            return "未登录：需要用户身份才能发起付款。";
        }
        if (user.role() == UserPrincipal.Role.EMPLOYEE) {
            return "权限不足：发起付款需要客服或管理员权限。";
        }
        Expense expense = find(expenseId);
        if (expense == null) {
            return "报销单 %s 不存在。".formatted(expenseId);
        }
        if (!canAccessRow(user, expense)) {
            return "权限不足：报销单 %s 不在你可操作的范围内。".formatted(expenseId);
        }

        // 举手报告：把待付款信息写进 hooks（业务代码在 ToolContext 里放的共享 Map），
        // 图引擎的审批门节点在模型返回后读到它，挂起流程等人工审批
        @SuppressWarnings("unchecked")
        Map<String, Object> hooks = (Map<String, Object>) context.getContext().get("hooks");
        if (hooks != null) {
            hooks.put("pendingPayment", expense.expenseId());
            hooks.put("pendingAmount", expense.amount());
        }
        return "报销单 %s（%.2f 元）已提交付款审批，等待人工批准后执行。"
                .formatted(expense.expenseId(), expense.amount());
    }

    /** 行级规则：本人或管理员（财务/付款场景比订单更严格：同部门也不可见） */
    static boolean canAccessRow(UserPrincipal user, Expense expense) {
        return user.role() == UserPrincipal.Role.ADMIN || expense.ownerUserId().equals(user.userId());
    }
}
