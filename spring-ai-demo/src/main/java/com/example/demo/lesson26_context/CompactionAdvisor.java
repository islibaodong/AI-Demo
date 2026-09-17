package com.example.demo.lesson26_context;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.Prompt;

import com.example.demo.lesson26_context.SessionCompactor.Result;

/**
 * 第 26 课：压缩 Advisor —— 把 SessionCompactor 接进 ChatClient 请求管线。
 *
 * <p>生产形态就是一个 Advisor：每次请求前检查会话历史是否超预算，
 * 超了就地压缩再放行——对调用方完全透明。实现 {@link BaseAdvisor}
 * （before/after 风格，框架默认的 adviseCall/adviseStream 会调度 before/after）。
 * 注意 order 的语义：<b>数值越小越靠外</b>（请求方向先执行、响应方向后执行）。
 * 压缩要排在记忆 Advisor <b>之后</b>（数值更大）——它压缩的是
 * 记忆装配完成后的最终历史。</p>
 */
public class CompactionAdvisor implements BaseAdvisor {

    private final SessionCompactor compactor;
    private final int order;

    public CompactionAdvisor(SessionCompactor compactor, int order) {
        this.compactor = compactor;
        this.order = order;
    }

    /** 请求前：超预算则压缩会话历史（台账放进 advisor context，可观测） */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        var instructions = request.prompt().getInstructions();
        if (!compactor.shouldCompact(instructions)) {
            return request;
        }
        Result result = compactor.compact(instructions);
        return request.mutate()
                .prompt(new Prompt(result.messages(), request.prompt().getOptions()))
                .context("compactionLedger", result.ledger())
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return order;
    }
}
