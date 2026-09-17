package com.example.demo.lesson13_security;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

/**
 * 第 13 课：输出侧防护 —— 敏感信息泄露扫描（call 与 stream 双路径）。
 *
 * <p><b>为什么输出侧还要防护？</b>输入侧（{@link PromptInjectionGuardAdvisor}）挡不住
 * 所有变形攻击；结构隔离（SystemMessage 分离）也只是"降低概率"而不是"杜绝"。
 * 纵深防御的最后一道防线是：就算模型真的吐出了秘密，也要在返回给用户之前拦住。</p>
 *
 * <p>原理：<b>金丝雀（canary）</b>——系统提示里埋一个真实系统里根本不存在的标记值，
 * 输出里出现它 = 100% 是泄露（不是误报）。本课用「SPR-SEC-DEMO-77」当金丝雀；
 * 生产中常用 UUID 当金丝雀，还可顺带追踪泄露源头（每个用户/会话发不同的）。</p>
 *
 * <p><b>两条路径的实现差异是本课重点</b>：</p>
 * <ul>
 *   <li>call（一次返回）：拿到完整文本，正则扫一遍即可。</li>
 *   <li>stream（逐 token 到达）：金丝雀可能被切在两个 chunk 的边界上
 *       （比如「SPR-」一个 chunk、「SEC-DEMO-77」另一个 chunk），逐 chunk 独立扫描会漏。
 *       解法是<b>滚动窗口</b>：把历史 chunk 拼起来扫，命中即截断流。</li>
 * </ul>
 *
 * <p>order = 90：靠内层（贴近模型），让输出在最终返回前必定经过这里。</p>
 */
public class SecretLeakGuard implements CallAdvisor, StreamAdvisor {

    /** 金丝雀标记：真实系统里不存在，输出中出现即判定泄露 */
    public static final String CANARY = "SPR-SEC-DEMO-77";

    /** 金丝雀的匹配模式（容忍被轻微变形） */
    static final java.util.regex.Pattern CANARY_PATTERN =
            java.util.regex.Pattern.compile("SPR-SEC-DEMO-\\w+");

    /** 注意措辞：警告文案本身也绝不能包含金丝雀值——否则防护器自己就成了泄露源 */
    private static final String BLOCKED_REPLY =
            "⚠ 输出防护：检测到疑似敏感信息泄露（命中金丝雀规则），内容已屏蔽。";

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    /** 输出扫描放在内层，保证所有 Advisor 处理完之后的最终输出都会被扫到 */
    @Override
    public int getOrder() {
        return 90;
    }

    // ---------- call 路径：整段扫描 + 掩码 ----------

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        String text = textOf(response);
        if (text != null && CANARY_PATTERN.matcher(text).find()) {
            return blocked();
        }
        return response;
    }

    // ---------- stream 路径：滚动窗口扫描，命中即截断流 ----------

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        StringBuilder window = new StringBuilder();
        AtomicBoolean cut = new AtomicBoolean(false);

        return chain.nextStream(request)
                .map(response -> {
                    if (cut.get()) {
                        return response;   // 截断后剩余 chunk 原样透传，由 takeUntil 丢弃
                    }
                    // 金丝雀可能跨 chunk 边界，必须拼上历史 chunk 再扫
                    window.append(textOf(response));
                    if (CANARY_PATTERN.matcher(window).find()) {
                        cut.set(true);
                        return blocked();
                    }
                    return response;
                })
                // takeUntil：发出第一个命中标记的元素（即警告消息）后立即完成流
                .takeUntil(response -> cut.get());
    }

    // ---------- 工具方法（静态以便离线单测直接驱动） ----------

    private static String textOf(ChatClientResponse response) {
        if (response.chatResponse() == null || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return "";
        }
        return response.chatResponse().getResult().getOutput().getText();
    }

    private static ChatClientResponse blocked() {
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage(BLOCKED_REPLY)))),
                java.util.Map.of());
    }
}
