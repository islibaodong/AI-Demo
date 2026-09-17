package com.example.demo.lesson23_memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springaicommunity.agent.tools.AutoMemoryTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import com.example.demo.support.ScriptedToolCallingChatModel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 23 课单元测试：不调用任何模型。验证长期记忆的两件事：
 * ① Agent 能在工具循环里真的把记忆写进文件（mock 模型返回工具调用）；
 * ② 会话窗口（ChatMemory）与长期记忆两层共存；③ 记忆文件 CRUD 语义。
 */
class Lesson23MemoryTest {

    @TempDir
    Path tmp;

    @Test
    void agentWritesLongTermMemoryThroughToolLoop() {
        // 完整链路：ChatClient → AutoMemoryToolsAdvisor 注入记忆工具 → 脚本模型发起
        // MemoryCreate → 模型内部循环真执行（文件落盘）→ 结果喂回 → 最终回答
        //
        // 注意：2.0 的工具循环在模型内部驱动，Mockito mock 的 ChatModel 不会执行工具，
        // 必须用 ScriptedToolCallingChatModel（见 support 包说明）
        ChatModel model = new ScriptedToolCallingChatModel(List.of(
                // 第 1 轮：模型决定调用记忆工具
                new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "t1", "function", "MemoryCreate",
                                "{\"path\": \"user_profile.md\", \"fileText\": \"---\\nname: user profile\\n"
                                        + "description: 小明的偏好\\ntype: user\\n---\\n喜欢简洁的回复\"}")))
                        .build()))),
                // 第 2 轮：工具结果喂回后给出最终回答
                new ChatResponse(List.of(new Generation(new AssistantMessage("已记住你的偏好"))))));
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(AutoMemoryToolsAdvisor.builder()
                        .memoriesRootDirectory(tmp.toString()).build())
                .build();

        String answer = client.prompt().user("请记住我喜欢简洁的回复").call().content();

        assertThat(answer).isEqualTo("已记住你的偏好");
        // 记忆真的写进了文件——这就是"跨会话可查"的物理载体
        assertThat(Files.exists(tmp.resolve("user_profile.md"))).isTrue();
        AutoMemoryTools tools = AutoMemoryTools.builder().memoriesDir(tmp).build();
        assertThat(tools.memoryView("user_profile.md", null))
                .contains("type: user").contains("喜欢简洁的回复");
    }

    @Test
    void sessionWindowAndLongTermMemoryCoexist() {
        // 两层记忆并存：会话窗口（自动记每轮）+ 长期记忆（模型策展）——同一 ChatClient
        ChatModel model = Mockito.mock(ChatModel.class);
        Mockito.when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        Mockito.when(model.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok")))));

        ChatMemory window = MessageWindowChatMemory.builder().maxMessages(10).build();
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(
                        // 会话 id 的 param key 是字面量 "chat_memory_conversation_id"（lesson04 的坑）
                        MessageChatMemoryAdvisor.builder(window).build(),
                        AutoMemoryToolsAdvisor.builder()
                                .memoriesRootDirectory(tmp.toString()).build())
                .build();

        ChatResponse response = client.prompt()
                .user("你好")
                .advisors(a -> a.param("chat_memory_conversation_id", "s1"))
                .call()
                .chatResponse();
        assertThat(response).isNotNull();
        assertThat(response.getResult().getOutput().getText()).isEqualTo("ok");
        // 会话窗口里留下了这轮对话（短期层），长期记忆目录保持未动（模型没决定写就没写）
        assertThat(window.get("s1")).isNotEmpty();
        assertThat(Files.exists(tmp.resolve("MEMORY.md"))).isFalse();
    }

    @Test
    void memoryFileCrudSemantics() {
        AutoMemoryTools tools = AutoMemoryTools.builder().memoriesDir(tmp).build();

        // 创建 → 精确替换（巩固/修正记忆）→ 删除 → 查不到了
        tools.memoryCreate("feedback.md", "回答要简洁。\n**Why:** 用户嫌啰嗦。\n");
        assertThat(tools.memoryStrReplace("feedback.md", "回答要简洁。", "回答先给结论再给细节。"))
                .doesNotStartWith("Error");
        assertThat(tools.memoryView("feedback.md", null)).contains("先给结论");
        assertThat(tools.memoryDelete("feedback.md")).doesNotStartWith("Error");
        assertThat(tools.memoryView("feedback.md", null)).contains("does not exist");
    }
}
