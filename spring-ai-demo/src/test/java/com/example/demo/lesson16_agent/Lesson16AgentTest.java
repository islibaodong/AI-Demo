package com.example.demo.lesson16_agent;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 16 课单元测试：不调用任何模型。验证治理层的审批门、工具调用预算、
 * 审计轨迹，以及"模型看到的工具定义不变"这一关键约束。
 */
class Lesson16AgentTest {

    /** 桩工具：记录是否真的被执行（高危工具的"真执行"必须可控） */
    private ToolCallback stubTool(String name) {
        AtomicInteger fired = new AtomicInteger();
        ToolDefinition def = DefaultToolDefinition.builder()
                .name(name)
                .description("测试工具 " + name)
                .inputSchema("{}")
                .build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String toolInput) {
                fired.incrementAndGet();
                return name + " 真实执行：" + toolInput;
            }
        };
    }

    // ---------- 审计 + 放行 ----------

    @Test
    void governorExecutesNormalToolAndLogsIt() {
        ToolCallback real = stubTool("queryOrder");
        AgentGovernor governor = new AgentGovernor(5, Set.of("applyRefund"));

        String result = governor.governCall("{\"orderId\": \"A1001\"}", real);

        assertThat(result).contains("queryOrder 真实执行");
        assertThat(governor.auditLog()).containsExactly("[执行] queryOrder({\"orderId\": \"A1001\"})");
        assertThat(governor.executedCalls()).isEqualTo(1);
    }

    // ---------- 审批门 ----------

    @Test
    void governorBlocksHighRiskToolWithoutExecutingIt() {
        AtomicInteger fired = new AtomicInteger();
        ToolDefinition def = DefaultToolDefinition.builder()
                .name("applyRefund").description("退款").inputSchema("{}").build();
        ToolCallback real = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String toolInput) {
                fired.incrementAndGet();
                return "已退款";
            }
        };
        AgentGovernor governor = new AgentGovernor(5, Set.of("applyRefund"));

        String result = governor.governCall("{\"orderId\": \"A1001\"}", real);

        // 高危工具根本没执行，返回的是"审批门话术 + 替代方案"，模型据此改道
        assertThat(fired.get()).isZero();
        assertThat(result).contains("人工审批").contains("createTicket");
        assertThat(governor.auditLog().get(0)).startsWith("[拦截]");
        assertThat(governor.executedCalls()).isZero();
    }

    // ---------- 工具调用预算（跨工具共享） ----------

    @Test
    void budgetIsSharedAcrossToolsAndGuidesModelToFinish() {
        AgentGovernor governor = new AgentGovernor(2, Set.of());
        ToolCallback a = stubTool("queryOrder");
        ToolCallback b = stubTool("notifyUser");

        assertThat(governor.governCall("1", a)).contains("真实执行");
        assertThat(governor.governCall("2", b)).contains("真实执行");

        // 第三次（哪怕是另一个工具）被预算拦下，返回"引导收尾"而不是抛异常
        String third = governor.governCall("3", a);
        assertThat(third).contains("预算已用尽").contains("最终答复");
        assertThat(governor.auditLog().get(2)).startsWith("[拦截]");
        assertThat(governor.executedCalls()).isEqualTo(2);
    }

    // ---------- 装饰器对模型透明 ----------

    @Test
    void governedToolKeepsDefinitionUnchanged() {
        // 模型看到的工具定义必须与真实工具完全一致——治理只管执行，不改"菜单"
        ToolCallback raw = rawTools()[0];
        AgentGovernor governor = new AgentGovernor(6, Set.of("applyRefund"));
        Lesson16Controller.GovernedTool governed = new Lesson16Controller.GovernedTool(governor, raw);

        assertThat(governed.getToolDefinition().name()).isEqualTo(raw.getToolDefinition().name());
        assertThat(governed.getToolDefinition().description())
                .isEqualTo(raw.getToolDefinition().description());
        assertThat(governed.getToolDefinition().inputSchema())
                .isEqualTo(raw.getToolDefinition().inputSchema());
    }

    private static ToolCallback[] rawTools() {
        return org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                .toolObjects(new AfterSaleTools())
                .build()
                .getToolCallbacks();
    }

    // ---------- 参数解析 ----------

    @Test
    void parseApprovalsHandlesCsvAndBlank() {
        assertThat(Lesson16Controller.parseApprovals("applyRefund, deleteOrder"))
                .containsExactlyInAnyOrder("applyRefund", "deleteOrder");
        assertThat(Lesson16Controller.parseApprovals("")).isEmpty();
        assertThat(Lesson16Controller.parseApprovals(null)).isEmpty();
    }

    // ---------- 真实工具集装配 ----------

    @Test
    void afterSaleToolsExposeFiveBusinessTools() {
        ToolCallback[] tools = rawTools();
        List<String> names = java.util.Arrays.stream(tools)
                .map(t -> t.getToolDefinition().name())
                .toList();
        assertThat(names).containsExactlyInAnyOrder(
                "queryOrder", "checkRefundPolicy", "applyRefund", "createTicket", "notifyUser");
    }
}
