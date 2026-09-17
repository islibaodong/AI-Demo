package com.example.demo.lesson16_agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.tool.ToolCallback;

/**
 * 第 16 课：Agent 治理层 —— 用<b>装饰器</b>接管每一次工具执行。
 *
 * <p><b>Agent 是什么？</b>模型 + 工具 + 循环：模型看工具清单自主决定下一步，
 * 框架自动执行工具并把结果喂回，循环到模型给出最终回答。Spring AI 的
 * 内置循环（{@code .toolCallbacks(...)}）就是这条链路——但它有两个生产级问题：</p>
 * <ul>
 *   <li><b>黑盒</b>：模型调了哪些工具、参数是什么，业务代码看不见（只能翻 DEBUG 日志）；</li>
 *   <li><b>失控</b>：2.0 的内置循环<b>没有步数上限</b>（字节码确认无任何限制配置），
 *       模型陷入工具调用循环就一直烧钱；高危工具（退款/删除）也是模型说调就调。</li>
 * </ul>
 *
 * <p><b>解法：装饰器</b>（见 {@link Lesson16Controller.GovernedTool}）。
 * 模型看到的工具定义<b>原封不动</b>，但 {@code call()} 被治理层接管。三个治理动作：</p>
 * <ol>
 *   <li><b>审计</b>：每次调用记入轨迹（谁、什么参数、放行还是拦截）——
 *       这就是 /lesson16/agent 返回的 toolCalls 轨迹，也是 lesson14 指标的天然数据源；</li>
 *   <li><b>预算</b>：全部工具共享一个执行次数上限（跨工具累计），超限后工具
 *       "假装应答"并返回<b>引导收尾</b>的话术——把预算限制以工具结果的形式告诉模型，
 *       让它自然停下，而不是抛异常炸掉整条链路；</li>
 *   <li><b>审批门</b>：高危工具直接拦截并返回"需人工审批 + 建议替代方案"，
 *       模型会读这个结果自行改道（本课演示：applyRefund 被拦后模型改创工单）。</li>
 * </ol>
 *
 * <p><b>与 LangChain 对照</b>：这层等价于 LangGraph 的 human-in-the-loop 断点 +
 * ToolNode 的自定义；审批门思想同 lesson13 的"工具最小权限"，但那里是
 * <b>事前</b>（不暴露给模型），这里是<b>事中</b>（暴露但执行前拦截）。</p>
 */
public class AgentGovernor {

    private final int maxToolCalls;
    private final Set<String> approvalRequired;

    private final AtomicInteger executedCalls = new AtomicInteger();
    private final List<String> auditLog = Collections.synchronizedList(new ArrayList<>());

    public AgentGovernor(int maxToolCalls, Set<String> approvalRequired) {
        this.maxToolCalls = maxToolCalls;
        this.approvalRequired = approvalRequired;
    }

    /** 核心治理逻辑：所有工具的执行都从这里过（target 是真正要执行的工具） */
    public String governCall(String toolInput, ToolCallback target) {
        String name = target.getToolDefinition().name();

        // 1) 审批门：高危工具直接拦截（根本不碰真实工具）
        if (approvalRequired.contains(name)) {
            auditLog.add("[拦截] %s(%s) 高危操作需人工审批".formatted(name, toolInput));
            return ("治理层：%s 是高危操作，需要人工审批，本次未执行。"
                    + "请改为调用 createTicket 创建工单转人工处理。").formatted(name);
        }

        // 2) 预算：全部工具共享的上限，超限后不再执行
        if (executedCalls.get() >= maxToolCalls) {
            auditLog.add("[拦截] %s(%s) 超出工具调用预算 %d".formatted(name, toolInput, maxToolCalls));
            return "治理层：本次任务的工具调用预算已用尽，请基于已有信息直接给出最终答复，不要再调用任何工具。";
        }

        // 3) 放行 + 审计
        executedCalls.incrementAndGet();
        auditLog.add("[执行] %s(%s)".formatted(name, toolInput));
        return target.call(toolInput);
    }

    /** 只读快照：给 Controller 组装响应（轨迹 + 用量） */
    public List<String> auditLog() {
        synchronized (auditLog) {
            return List.copyOf(auditLog);
        }
    }

    public int executedCalls() {
        return executedCalls.get();
    }

    public int maxToolCalls() {
        return maxToolCalls;
    }
}
