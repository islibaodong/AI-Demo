package com.example.demo.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * 测试专用的脚本化 ChatModel（离线测试基建，多个课程测试共用）。
 *
 * <p><b>关键事实（探针实测）</b>：ChatClient 的工具循环由自动注册的
 * {@code ToolCallingAdvisor} 驱动，但它只在请求 options 是
 * {@link ToolCallingChatOptions} 时才介入——而 options 从<b>模型的
 * getOptions() 复制而来</b>（DefaultChatClientUtils）。真实 OpenAiChatModel 的
 * 默认 options 就是 ToolCallingChatOptions，所以生产环境一切正常；
 * Mockito mock 返回普通 ChatOptions 时整条工具链路会被静默跳过
 * （模型返回的工具调用永远不执行）。</p>
 *
 * <p>本类因此做两件事：① {@code getOptions()} 返回 ToolCallingChatOptions，
 * 让 ToolCallingAdvisor 正常接管"模型 → 执行工具 → 喂回 → 再问模型"的循环；
 * ② 模型回答按脚本顺序出队——只有最终文本是假的，工具调用会被 Advisor
 * 真实执行，从而能离线验证完整 Agent 行为。</p>
 *
 * <p>脚本耗尽时抛异常，防止测试因模型被多调一轮而静默失真。</p>
 */
public class ScriptedToolCallingChatModel implements ChatModel {

    private final Deque<ChatResponse> script = new ArrayDeque<>();
    /** 每次被调时收到的 Prompt（供测试断言"模型看到了什么"，如工具结果是否回流） */
    private final List<Prompt> observedCalls = new CopyOnWriteArrayList<>();

    public ScriptedToolCallingChatModel(List<ChatResponse> script) {
        this.script.addAll(script);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        observedCalls.add(prompt);
        ChatResponse response = script.poll();
        if (response == null) {
            throw new IllegalStateException("脚本响应已耗尽：模型被调用的次数超过了脚本预设的轮数");
        }
        return response;
    }

    /** 测试断言用：模型历次被调时收到的 Prompt 快照 */
    public List<Prompt> observedCalls() {
        return List.copyOf(observedCalls);
    }

    /** 必须是 ToolCallingChatOptions：ToolCallingAdvisor 靠它识别"这条链路要跑工具循环" */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().build();
    }
}
