package com.example.demo.lesson16_agent;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 第 16 课：售后场景的业务工具集。
 *
 * <p><b>业务场景：售后工单自动处理</b>——用户说"订单 A1001 有质量问题要退款"，
 * Agent 应自主完成多步链路：查订单 → 核对政策 → 执行处理（退款/建工单）→ 通知用户 → 总结。</p>
 *
 * <p><b>本课所有工具都是假实现</b>（固定返回，不碰真业务系统），重点在
 * "模型如何自主串起多步调用"，以及调用轨迹如何被治理层接管（{@link AgentGovernor}）。</p>
 *
 * <p><b>与 LangChain 对照</b>：@Tool ≈ Python 的 @tool 装饰器；工具集 ≈
 * ToolNode / bind_tools 的函数列表。</p>
 */
public class AfterSaleTools {

    @Tool(description = "按订单号查询订单信息（状态、下单天数、金额）")
    public String queryOrder(String orderId) {
        return "订单 %s：状态=已签收，下单天数=12 天，金额=129 元".formatted(orderId);
    }

    @Tool(description = "检查订单是否符合售后政策（7 天无理由退货等）")
    public String checkRefundPolicy(String orderId) {
        return "订单 %s：已超 7 天无理由期限（下单 12 天），仅支持质量问题退款".formatted(orderId);
    }

    /** 高危工具：真金白银的操作，治理层（AgentGovernor）会对它加审批门 */
    @Tool(description = "申请退款（高危操作，涉及资金）")
    public String applyRefund(String orderId) {
        return "订单 %s 已退款 129 元".formatted(orderId);
    }

    @Tool(description = "创建售后工单，转人工处理")
    public String createTicket(String orderId, String issue) {
        return "已创建工单 TT-9042（订单 %s，问题：%s），预计 24 小时内人工跟进".formatted(orderId, issue);
    }

    @Tool(description = "通知用户处理进度（短信/站内信）")
    public String notifyUser(String orderId, String message) {
        return "已向订单 %s 的用户发送通知：%s".formatted(orderId, message);
    }
}
