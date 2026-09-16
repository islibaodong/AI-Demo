package com.example.demo.lesson08_advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.StringUtils;

/**
 * 第 8 课：自定义 Advisor 之二 —— 日志审计。
 *
 * <p>演示更省事的 {@link BaseAdvisor} 写法：只实现 {@link #before} / {@link #after}
 * 两个钩子。框架的 default 方法会自动把它们串成
 * 「before → 下游（其它 Advisor / 模型）→ after」，且 call 与 stream(Flux) 两条路径都能用；
 * 需要短路或深度改写时，才用 {@link SensitiveWordAdvisor} 那种 CallAdvisor 全手动写法。</p>
 *
 * <p>before 把起始时间戳放进共享的 <code>context</code>（一次请求内所有 Advisor 共享同一张表），
 * after 取出来算耗时——这是 Advisor 之间传递数据的标准做法。</p>
 *
 * <p>order = 100：数值越大越靠内层（越贴近模型）。放最内层，
 * 日志里看到的是被前面所有 Advisor 处理完之后的「最终 prompt」与「最终响应」。</p>
 *
 * <p>观察方式：调用 /lesson8/chat 时看控制台，本类把审计信息打到 stdout。</p>
 */
public class AuditAdvisor implements BaseAdvisor {

    private static final String START_TIME_KEY = "audit_start_time";

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    /** 越大越靠内层：审计放在最里面，记录的是最终形态的 prompt。 */
    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        request.context().put(START_TIME_KEY, System.currentTimeMillis());
        String q = request.prompt().getUserMessage().getText();
        // 注意这里是"最终 prompt"：敏感词已被前面的 Advisor 打码
        System.out.printf("[lesson08][Audit] 请求进入模型前，用户问题 = %s%n", q);
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        long costMs = System.currentTimeMillis() - (long) response.context().getOrDefault(START_TIME_KEY, 0L);
        String answer = "";
        ChatResponse chat = response.chatResponse();
        if (chat != null && chat.getResult() != null && chat.getResult().getOutput() != null) {
            answer = StringUtils.hasText(chat.getResult().getOutput().getText())
                    ? chat.getResult().getOutput().getText() : "（空）";
        }
        System.out.printf("[lesson08][Audit] 模型返回，耗时 %d ms，回答 %d 字，前 60 字 = %s%n",
                costMs, answer.length(), answer.substring(0, Math.min(60, answer.length())));
        return response;
    }
}
