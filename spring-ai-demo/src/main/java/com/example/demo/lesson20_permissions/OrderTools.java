package com.example.demo.lesson20_permissions;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 第 20 课：带权限的订单工具 —— 工具级 RBAC + 行级数据权限都在<b>工具内部</b>执行。
 *
 * <p><b>身份从哪来？</b>每个工具方法声明一个 {@link ToolContext} 参数（框架识别类型自动注入，
 * 不占用模型看到的参数 schema），身份是 Controller 侧 {@code .toolContext(Map.of("user", principal))}
 * 放进去的。<b>权限判断必须以 ToolContext 里的身份为准</b>，绝不相信模型参数里自称的
 * "我是管理员"——模型输出是可被诱导的（lesson13 注入靶场）。</p>
 *
 * <p><b>2.0 实测边界</b>：框架对声明了 ToolContext 的工具要求<b>非空上下文</b>
 * （{@code validateToolContextSupport}：context 为 null 或空 map 时直接抛
 * IllegalArgumentException，工具方法不会执行）。所以"未登录"的真实形态是
 * "上下文里没放 user"，工具内 {@code requireLogin} 兜的就是这一层。</p>
 *
 * <p>两层权限，都是<b>数据层的事后闸门</b>（模型已经决定要调这个工具了）：</p>
 * <ul>
 *   <li><b>工具级 RBAC</b>：这个角色能不能用这个工具？（cancelOrder 要客服以上）</li>
 *   <li><b>行级数据权限</b>：这条数据归不归我？（本人/同部门/管理员才能看订单明细）</li>
 * </ul>
 *
 * <p>拒绝时返回<b>拒绝话术</b>而不是抛异常——与 lesson16 预算超限同一思路：
 * 把约束写进工具结果，模型读到后自然向用户解释，链路不炸。</p>
 *
 * <p><b>与 LangChain 对照</b>：≈ 自定义 {@code @tool} 里读 {@code RunnableConfig} 的
 * 用户上下文做鉴权；行级过滤 ≈ 数据层 WHERE owner = current_user。</p>
 */
public class OrderTools {

    // ---------- 内存订单库（生产里这是业务 DB，行级权限 = SQL 的 WHERE 条件） ----------

    /** 一条订单：归属人 + 归属部门是行级权限的判定依据 */
    public record Order(String orderId, String ownerUserId, String dept, String item, double amount, String status) {
    }

    private static final List<Order> ORDERS = List.of(
            new Order("A1001", "carol", "研发部", "机械键盘", 399.0, "已签收"),
            new Order("A1002", "bob", "客服部", "降噪耳机", 1299.0, "配送中"),
            new Order("A1003", "alice", "管理部", "办公椅", 2999.0, "已签收"),
            new Order("A1004", "carol", "研发部", "显示器", 1899.0, "已发货"));

    private static Order find(String orderId) {
        return ORDERS.stream().filter(o -> o.orderId().equalsIgnoreCase(orderId)).findFirst().orElse(null);
    }

    // ---------- 工具 1：查订单（行级数据权限） ----------

    @Tool(description = "按订单号查询订单详情。只能查询本人或同部门的订单，管理员可查全部。")
    public String queryOrder(@ToolParam(description = "订单号，如 A1001") String orderId,
            ToolContext context) {
        UserPrincipal user = UserPrincipal.fromContext(context.getContext());
        String deny = requireLogin(user);
        if (deny != null) {
            return deny;
        }
        Order order = find(orderId);
        if (order == null) {
            return "订单 %s 不存在。".formatted(orderId);
        }
        if (!canAccessRow(user, order)) {
            return ("权限不足：订单 %s 归属 %s（%s），你只能查询本人或同部门的订单。"
                    + "请不要向用户透露该订单的任何信息。").formatted(orderId, order.ownerUserId(), order.dept());
        }
        return "%s：%s，金额 %.2f 元，状态：%s（下单人：%s / %s）"
                .formatted(order.orderId(), order.item(), order.amount(), order.status(),
                        order.ownerUserId(), order.dept());
    }

    // ---------- 工具 2：我的订单（天然行级：直接按身份过滤，无 orderId 参数可越权） ----------

    @Tool(description = "查询当前登录用户自己的全部订单。")
    public String listMyOrders(ToolContext context) {
        UserPrincipal user = UserPrincipal.fromContext(context.getContext());
        String deny = requireLogin(user);
        if (deny != null) {
            return deny;
        }
        List<Order> mine = ORDERS.stream().filter(o -> o.ownerUserId().equals(user.userId())).toList();
        if (mine.isEmpty()) {
            return "你没有订单。";
        }
        return mine.stream()
                .map(o -> "%s：%s，%.2f 元，%s".formatted(o.orderId(), o.item(), o.amount(), o.status()))
                .collect(Collectors.joining("；"));
    }

    // ---------- 工具 3：取消订单（工具级 RBAC + 行级双闸门） ----------

    @Tool(description = "取消指定订单。仅客服和管理员可执行，且仍受订单归属限制。")
    public String cancelOrder(@ToolParam(description = "订单号") String orderId, ToolContext context) {
        UserPrincipal user = UserPrincipal.fromContext(context.getContext());
        String deny = requireLogin(user);
        if (deny != null) {
            return deny;
        }
        // 第一道闸：工具级 RBAC——普通员工根本没有"取消订单"这个操作权限
        if (user.role() == UserPrincipal.Role.EMPLOYEE) {
            return "权限不足：取消订单需要客服或管理员权限，如需取消请联系人工客服。";
        }
        // 第二道闸：行级——客服也只能取消自己/同部门的订单（不是"有工具权限就能动全部数据"）
        Order order = find(orderId);
        if (order == null) {
            return "订单 %s 不存在。".formatted(orderId);
        }
        if (!canAccessRow(user, order)) {
            return "权限不足：订单 %s 不在你可操作的范围内（仅本人或同部门订单）。".formatted(orderId);
        }
        return "订单 %s 已取消（操作人：%s）。".formatted(orderId, user.name());
    }

    // ---------- 工具 4：全公司营收报表（维度级 RBAC：只有管理员能跨全公司聚合） ----------

    @Tool(description = "查询全公司订单总额报表。仅管理员可用。")
    public String companyRevenueReport(ToolContext context) {
        UserPrincipal user = UserPrincipal.fromContext(context.getContext());
        String deny = requireLogin(user);
        if (deny != null) {
            return deny;
        }
        if (user.role() != UserPrincipal.Role.ADMIN) {
            return "权限不足：公司级报表仅管理员可查询。";
        }
        double total = ORDERS.stream().mapToDouble(Order::amount).sum();
        Map<String, Double> byDept = ORDERS.stream().collect(
                Collectors.groupingBy(Order::dept, Collectors.summingDouble(Order::amount)));
        String detail = byDept.entrySet().stream()
                .map(e -> "%s %.2f 元".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining("；"));
        return "全公司订单总额 %.2f 元。按部门：%s。".formatted(total, detail);
    }

    // ---------- 权限判定（包级可见，离线单测直接复用） ----------

    /** 行级规则：本人 / 同部门 / 管理员 可见可操作 */
    static boolean canAccessRow(UserPrincipal user, Order order) {
        return user.role() == UserPrincipal.Role.ADMIN
                || order.ownerUserId().equals(user.userId())
                || order.dept().equals(user.dept());
    }

    /** 未登录拒绝话术；已登录返回 null */
    static String requireLogin(UserPrincipal user) {
        return user == null ? "未登录：工具需要用户身份（ToolContext 里没有 user）。请提示用户先登录。" : null;
    }
}
