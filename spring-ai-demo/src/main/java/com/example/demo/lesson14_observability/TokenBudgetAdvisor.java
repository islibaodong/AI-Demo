package com.example.demo.lesson14_observability;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 第 14 课：Token 预算防护 —— 成本失控的"最后一道闸"。
 *
 * <p><b>为什么需要它？</b>单价表（{@link ModelPricing}）解决"事后算账"，
 * 预算防护解决"事前止损"：失控场景很现实——某个客户端陷入重试循环、某个
 * Agent 无限递归调用工具、某个用户拿着脚本刷接口。没有上限，错误会一直
 * 产生费用直到有人发现账单。</p>
 *
 * <p><b>机制</b>：Advisor 内部维护一个累计器，每次真实调用后把本次
 * {@link Usage#getTotalTokens()} 累加；下一次请求进来时先检查——
 * 已达上限就<b>短路</b>（复用 lesson13 的拦截模式），请求不出网、零成本。</p>
 *
 * <p><b>教学简化与生产差距</b>（都要知道）：</p>
 * <ul>
 *   <li>这里一个 Advisor 实例 = 一个预算池，进程内累计。生产要按<b>会话/用户/租户</b>
 *       维度隔离（放 Redis 之类的外部存储，多实例共享）。</li>
 *   <li>窗口语义：这是"累计封顶"，生产常用<b>滑动窗口配额</b>（如每小时 N token），
 *       思路相同、累计器换成带过期时间的计数即可。</li>
 *   <li>预算检查放在<b>调用前</b>只是硬闸；配套还需要告警（80% 阈值通知），
 *       靠 lesson14 的 metrics 数据接 Prometheus 规则实现。</li>
 * </ul>
 *
 * <p>order = -50：外层（输入拦截类），预算耗尽时请求连内层 Advisor 都不该见到。</p>
 */
public class TokenBudgetAdvisor implements CallAdvisor {

    private final int tokenLimit;

    /** LongAdder 而非 AtomicLong：计数器场景（写多读少）无 CAS 自旋竞争 */
    private final LongAdder usedTokens = new LongAdder();

    public TokenBudgetAdvisor(int tokenLimit) {
        this.tokenLimit = tokenLimit;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return -50;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (usedTokens.sum() >= tokenLimit) {
            // 短路：预算耗尽，请求不出网。拼接待格式化的文案要先加括号再 .formatted()
            //（lesson13 的坑：formatted 只绑定最后一个字符串字面量）
            String reply = ("（预算防护：累计 token 已达上限 %d，本次请求未调用模型。）")
                    .formatted(tokenLimit);
            return new ChatClientResponse(
                    new ChatResponse(List.of(new Generation(new AssistantMessage(reply)))),
                    request.context());
        }
        ChatClientResponse response = chain.nextCall(request);
        // 真实调用完成后累计本次用量——注意要在返回前累计，保证并发下的近似准确
        usedTokens.add(totalTokensOf(response));
        return response;
    }

    public long usedTokens() {
        return usedTokens.sum();
    }

    public long remaining() {
        return Math.max(0, tokenLimit - usedTokens.sum());
    }

    /** 安全取本次总 token：API 没返回用量时按 0 处理，绝不让防护本身抛异常 */
    static long totalTokensOf(ChatClientResponse response) {
        if (response.chatResponse() == null || response.chatResponse().getMetadata() == null) {
            return 0;
        }
        Usage usage = response.chatResponse().getMetadata().getUsage();
        Integer total = usage == null ? null : usage.getTotalTokens();
        return total == null ? 0 : total;
    }
}
