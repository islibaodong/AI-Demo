package com.example.demo.lesson20_permissions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 20 课单元测试：不调用任何模型。验证三层权限：
 * 工具级 RBAC、行级数据权限（真实走 Spring AI 的 ToolContext 通路）、检索层密级过滤。
 */
class Lesson20PermissionsTest {

    // ---------- 工具直调（真实 MethodToolCallback + ToolContext 通路） ----------

    private static String call(String toolName, String json, UserPrincipal user) {
        ToolCallback[] tools = org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                .toolObjects(new OrderTools())
                .build()
                .getToolCallbacks();
        ToolCallback callback = java.util.Arrays.stream(tools)
                .filter(t -> t.getToolDefinition().name().equals(toolName))
                .findFirst()
                .orElseThrow();
        // 注意：2.0 对声明了 ToolContext 参数的工具要求"非空上下文"（空 map 会在框架层
        // 直接拒绝、工具不执行），所以"未登录"用"有上下文但没放 user"来模拟
        Map<String, Object> ctx = user == null ? Map.of("session", "anonymous")
                : Map.of("user", user);
        return callback.call(json, new ToolContext(ctx));
    }

    // ---------- 行级数据权限 ----------

    @Test
    void employeeCannotReadOtherDeptOrder() {
        // carol（研发部）查 bob（客服部）的订单：非本人、非同部门 → 拒绝
        String denied = call("queryOrder", "{\"orderId\": \"A1002\"}", UserPrincipal.resolve("carol"));
        assertThat(denied).contains("权限不足").contains("本人或同部门");
        // 不泄露任何订单信息
        assertThat(denied).doesNotContain("降噪耳机").doesNotContain("1299");
    }

    @Test
    void employeeCanReadOwnOrder() {
        String ok = call("queryOrder", "{\"orderId\": \"A1001\"}", UserPrincipal.resolve("carol"));
        assertThat(ok).contains("机械键盘").contains("已签收");
    }

    @Test
    void adminReadsAllRows() {
        String ok = call("queryOrder", "{\"orderId\": \"A1002\"}", UserPrincipal.resolve("alice"));
        assertThat(ok).contains("降噪耳机").doesNotContain("权限不足");
    }

    @Test
    void missingIdentityIsDenied() {
        // ToolContext 里没有 user（未登录）：拒绝，而不是 NPE 或放行
        String denied = call("queryOrder", "{\"orderId\": \"A1001\"}", null);
        assertThat(denied).contains("未登录");
    }

    // ---------- 工具级 RBAC ----------

    @Test
    void employeeCannotCancelOrder() {
        // carol 连自己的订单都不能取消——操作权限与数据可见是两回事
        String denied = call("cancelOrder", "{\"orderId\": \"A1001\"}", UserPrincipal.resolve("carol"));
        assertThat(denied).contains("权限不足").contains("客服或管理员");
    }

    @Test
    void supportCanCancelOwnButNotOtherDeptOrder() {
        // bob（客服）取消自己的订单：RBAC 过 + 行级过
        String ok = call("cancelOrder", "{\"orderId\": \"A1002\"}", UserPrincipal.resolve("bob"));
        assertThat(ok).contains("已取消");
        // bob 取消 carol 的订单：工具权限过了，行级闸门拦下——"有工具≠能动全部数据"
        String denied = call("cancelOrder", "{\"orderId\": \"A1001\"}", UserPrincipal.resolve("bob"));
        assertThat(denied).contains("权限不足").doesNotContain("已取消");
    }

    @Test
    void revenueReportIsAdminOnly() {
        assertThat(call("companyRevenueReport", "{}", UserPrincipal.resolve("carol")))
                .contains("权限不足");
        assertThat(call("companyRevenueReport", "{}", UserPrincipal.resolve("alice")))
                .contains("全公司订单总额").contains("研发部");
    }

    // ---------- 检索层密级过滤 ----------

    @Test
    void retrievalFiltersConfidentialForEmployee() {
        List<Document> corpus = Lesson20Controller.kbCorpus();
        UserPrincipal carol = UserPrincipal.resolve("carol");

        List<Document> hits = PermissionFilteredRetriever.searchFor(carol, "差旅报销", corpus, 3);
        String joined = hits.stream().map(Document::getText).reduce("", (a, b) -> a + b);
        // 机密口径（实报实销）根本不在候选集里——不是"叮嘱模型别说"，是不在场
        assertThat(joined).doesNotContain("实报实销");
    }

    @Test
    void adminSeesConfidentialInRetrieval() {
        List<Document> corpus = Lesson20Controller.kbCorpus();
        List<Document> hits = PermissionFilteredRetriever.searchFor(
                UserPrincipal.resolve("alice"), "高管差旅", corpus, 3);
        assertThat(hits.stream().map(Document::getText).reduce("", (a, b) -> a + b))
                .contains("实报实销");
    }

    @Test
    void unlabeledDocumentDefaultsToConfidential() {
        // 没标密级的文档按最机密处理（默认拒绝）——新文档忘打标不应该变成全员可见
        Document unlabeled = new Document("x#0", "某段内容", Map.of("docId", "x"));
        assertThat(PermissionFilteredRetriever.levelOf(unlabeled))
                .isEqualTo(UserPrincipal.DataLevel.CONFIDENTIAL);
        assertThat(UserPrincipal.resolve("carol").canSee(PermissionFilteredRetriever.levelOf(unlabeled)))
                .isFalse();
    }

    // ---------- 工具清单与身份目录 ----------

    @Test
    void orderToolsExposeFourBusinessTools() {
        List<String> names = java.util.Arrays.stream(org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                .toolObjects(new OrderTools())
                .build()
                .getToolCallbacks())
                .map(t -> t.getToolDefinition().name())
                .toList();
        assertThat(names).containsExactlyInAnyOrder(
                "queryOrder", "listMyOrders", "cancelOrder", "companyRevenueReport");
    }

    @Test
    void unknownUserResolvesToNull() {
        assertThat(UserPrincipal.resolve("mallory")).isNull();
        assertThat(UserPrincipal.resolve("alice")).isNotNull();
    }
}
