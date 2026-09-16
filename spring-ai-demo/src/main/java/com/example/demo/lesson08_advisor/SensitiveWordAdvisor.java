package com.example.demo.lesson08_advisor;

import java.util.List;
import java.util.Set;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 第 8 课：自定义 Advisor 之一 —— 敏感词过滤。
 *
 * <p>演示 CallAdvisor 的两种能力：</p>
 * <ol>
 *   <li><b>改写请求</b>：把用户消息里的敏感词替换成 ＊＊＊ 再放行（改写 prompt 的标准姿势：
 *       {@code request.mutate().prompt(prompt.augmentUserMessage(新文案)).build()}）。</li>
 *   <li><b>短路拦截</b>：命中黑名单词时<b>不调用 chain.nextCall()</b>，直接返回预设回答——
 *       请求根本不会到达模型，也不消耗 token。这是 Advisor 区别于「事后过滤」的关键能力。</li>
 * </ol>
 *
 * <p>order = -100：数值越小越先执行（越靠外层）。放在最外层，
 * 保证后续所有 Advisor（如审计、RAG）看到的都是清洗后的文本。</p>
 *
 * <p><b>与 LangChain 对照</b>：≈ LangChain 的 <code>Runnable.with_listeners / 中间件</code>，
 * 或 chain 里前置的 <code>RunnableLambda</code> 预处理步骤。</p>
 */
public class SensitiveWordAdvisor implements CallAdvisor {

    /** 只做打码、不影响回答的词 */
    private static final Set<String> MASK_WORDS = Set.of("密码", "身份证号");

    /** 直接拦截、不给模型看的词 */
    private static final Set<String> BLOCK_WORDS = Set.of("作弊");

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    /** 越小越靠外层。放在最外，让所有下游 Advisor 拿到的都是干净文本。 */
    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String userText = request.prompt().getUserMessage().getText();

        // 1) 黑名单 → 短路：不调 chain.nextCall()，请求到此为止
        String blocked = BLOCK_WORDS.stream().filter(userText::contains).findFirst().orElse(null);
        if (blocked != null) {
            String reply = "（已由敏感词 Advisor 拦截：请求包含「%s」，本次不调用模型。）".formatted(blocked);
            return new ChatClientResponse(
                    new ChatResponse(List.of(new Generation(new AssistantMessage(reply)))),
                    request.context());
        }

        // 2) 灰名单 → 改写后放行
        String masked = userText;
        for (String word : MASK_WORDS) {
            masked = masked.replace(word, "＊＊＊");
        }
        if (!masked.equals(userText)) {
            request = request.mutate()
                    .prompt(request.prompt().augmentUserMessage(masked))
                    .build();
        }
        return chain.nextCall(request);
    }
}
