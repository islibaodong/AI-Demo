package com.example.demo.lesson25_subagents;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;

import com.example.demo.support.ScriptedToolCallingChatModel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 25 课单元测试：不调用任何模型（脚本化模型）。
 * 验证 Agent Registry 装载、Task 工具暴露、以及"主代理 → 委派子代理 → 汇总"的
 * 完整委派闭环（Task 工具由 ToolCallingAdvisor 真实执行，子代理真跑独立 ChatClient）。
 */
class Lesson25SubagentTest {

    @Test
    void registryLoadsBothSubagentsFromMarkdown() {
        List<Lesson25Controller.SubagentInfo> registry = Lesson25Controller.loadRegistry();

        assertThat(registry).extracting(Lesson25Controller.SubagentInfo::name)
                .containsExactly("researcher", "writer");
        assertThat(registry.get(0).description()).contains("调研");
        assertThat(registry.get(1).description()).contains("撰写");
    }

    @Test
    void subagentReferencesResolveFromSameDirectory() {
        // TaskTool 内部用的解析器与我们展示用的 Registry 读的是同一批文件
        List<SubagentReference> refs = ClaudeSubagentReferences.fromRootDirectory(
                Lesson25Controller.AGENTS_DIR);
        assertThat(refs).hasSize(2);
    }

    @Test
    void taskToolIsExposedToModel() {
        ChatModel model = new ScriptedToolCallingChatModel(List.of(
                new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))))));
        ToolCallback taskTool = TaskTool.builder()
                .subagentTypes(ClaudeSubagentType.builder()
                        .chatClientBuilder("default", ChatClient.builder(model))
                        .build())
                .subagentReferences(
                        ClaudeSubagentReferences.fromRootDirectory(Lesson25Controller.AGENTS_DIR))
                .build();

        // 模型看到的 Task 工具：定义齐全，描述里带子代理清单（registry 的一级披露）
        assertThat(taskTool.getToolDefinition().name()).isNotBlank();
        assertThat(taskTool.getToolDefinition().description()).isNotBlank();
    }

    @Test
    void fullDelegationLoopMainToSubagentAndBack() {
        // 委派闭环（实测：主模型只被调两次——委派前发起工具调用、委派后汇总；
        // 子代理消耗自己的脚本；工具结果以 ToolResponse 消息回流，不经模型）：
        // ① 主代理决定委派 → Task 工具调用（ToolCallingAdvisor 真执行）
        // ② 子代理在独立 ChatClient 里执行 → 返回调研结果（子代理自己的脚本）
        // ③ 委派结果并入主代理会话历史 → 主代理第二次被调 → 汇总成最终回答
        ChatModel model = new ScriptedToolCallingChatModel(List.of(
                // 主模型第 1 次：决定委派
                new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("t1", "function", "Task",
                                "{\"description\": \"调研住宿标准\", \"prompt\": \"汇总报销资料要点\","
                                        + "\"subagent_type\": \"researcher\"}")))
                        .build()))),
                // 主模型第 2 次（工具结果已并入历史）：汇总成最终回答
                new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "已委派调研并汇总：一线城市 600 元/晚，二线城市 400 元/晚。"))))));

        // 子代理模型：在它自己的独立 ChatClient 里被调，输出带唯一标记的调研结果
        // （标记只存在于子代理脚本里——它出现在主代理第 2 轮的输入里，才能证明结果真实回流）
        ChatModel subagentModel = new ScriptedToolCallingChatModel(List.of(
                new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "调研完成：住宿标准一线城市 600 元/晚。（SUBAGENT-MARKER-42）"))))));
        ScriptedToolCallingChatModel scriptedSubagentModel = (ScriptedToolCallingChatModel) subagentModel;

        ToolCallback taskTool = TaskTool.builder()
                .subagentTypes(ClaudeSubagentType.builder()
                        .chatClientBuilder("default", ChatClient.builder(subagentModel))
                        .build())
                .subagentReferences(
                        ClaudeSubagentReferences.fromRootDirectory(Lesson25Controller.AGENTS_DIR))
                .build();

        ChatClient mainClient = ChatClient.builder(model)
                .defaultToolCallbacks(taskTool)
                .build();

        String answer = mainClient.prompt()
                .user("调研住宿标准并汇总")
                .call()
                .content();

        // 最终回答来自主代理的汇总轮
        assertThat(answer).contains("汇总").contains("600");

        // 证明 1（委派）：子代理被调过一次，且收到的是主代理派发的任务描述
        assertThat(scriptedSubagentModel.observedCalls()).hasSize(1);
        assertThat(scriptedSubagentModel.observedCalls().get(0).getInstructions())
                .anySatisfy(m -> assertThat(m.getText()).contains("汇总报销资料要点"));

        // 证明 2（回流）：主模型第 2 轮的输入里出现了子代理输出的唯一标记——
        // 它以 ToolResponseMessage 的 responseData 形式并入了主代理的会话历史
        // （注意工具消息的 getText() 返回空串，内容在 getResponses() 里）
        ScriptedToolCallingChatModel scriptedMainModel = (ScriptedToolCallingChatModel) model;
        assertThat(scriptedMainModel.observedCalls()).hasSize(2);
        assertThat(scriptedMainModel.observedCalls().get(1).getInstructions())
                .filteredOn(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage)
                .anySatisfy(m -> assertThat(
                        ((org.springframework.ai.chat.messages.ToolResponseMessage) m).getResponses()
                                .stream()
                                .map(org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse::responseData)
                                .reduce("", String::concat))
                        .contains("SUBAGENT-MARKER-42"));
    }
}
