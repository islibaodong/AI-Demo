package com.example.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springaicommunity.agent.tools.AutoMemoryTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 兼容性冒烟测试：验证 spring-ai-agent-utils 0.12.0 对本工程的 Spring AI <b>2.0.0</b> 二进制兼容。
 *
 * <p>0.12.0 的 POM 声明依赖 spring-ai-client-chat 2.0.1（补丁版）。本测试把四个核心模块
 * 真实跑一遍——类加载、Builder 组装、文件读写、Advisor 挂进 ChatClient 走完整链路——
 * 任何NoSuchMethodError/NoClassDefFoundError 都会在这里暴露：</p>
 * <ul>
 *   <li><b>AutoMemoryTools</b>（lesson23 长期记忆）：MEMORY.md 索引 + 记忆文件的创建/读取；</li>
 *   <li><b>SkillsTool</b>（lesson24 技能）：从目录发现 SKILL.md 并按名加载；</li>
 *   <li><b>AutoMemoryToolsAdvisor</b>：零样板把记忆工具+系统提示挂进请求管线（mock 模型全链路）；</li>
 *   <li><b>TaskTool</b>（lesson25 子代理）：Claude 型子代理注册 + 工具暴露（不真跑模型循环）。</li>
 * </ul>
 *
 * <p>全部离线：模型用 Mockito mock（stub {@code call(Prompt)} 返回固定回答）。</p>
 */
class AgentUtilsCompatTest {

    @TempDir
    Path tmp;

    private static ChatModel mockChatModel() {
        ChatModel model = Mockito.mock(ChatModel.class);
        Mockito.when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        Mockito.when(model.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok")))));
        return model;
    }

    // ---------- 1) 长期记忆：文件读写往返 ----------

    @Test
    void autoMemoryToolsFileRoundtrip() {
        AutoMemoryTools tools = AutoMemoryTools.builder().memoriesDir(tmp).build();

        // 两步保存法的第一步：建记忆文件（带 YAML frontmatter）
        String created = tools.memoryCreate("user_profile.md", """
                ---
                name: user profile
                description: Alice — backend engineer
                type: user
                ---
                Prefers concise answers.
                """);
        assertThat(created).doesNotStartWith("Error");

        // 索引文件
        assertThat(tools.memoryCreate("MEMORY.md", "- [User Profile](user_profile.md) — Alice, backend"))
                .doesNotStartWith("Error");

        // 读取：内容与落盘文件都验证
        assertThat(tools.memoryView("user_profile.md", null)).contains("Alice");
        assertThat(tools.memoryView("MEMORY.md", null)).contains("User Profile");
        assertThat(tools.getMemoriesDir()).isEqualTo(tmp);
        assertThat(Files.exists(tmp.resolve("user_profile.md"))).isTrue();

        // 重复创建同路径应被拒（防止覆盖记忆）
        assertThat(tools.memoryCreate("user_profile.md", "x")).contains("already exists");
    }

    // ---------- 2) 技能：SKILL.md 发现与按名加载 ----------

    @Test
    void skillsToolDiscoversAndLoadsSkill() throws Exception {
        Path skillDir = tmp.resolve("skills/expense-policy");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: expense-policy
                description: 报销政策专家——回答费用报销、差旅标准问题
                ---
                报销需在事项结束后 5 个工作日内提交发票。
                """);

        ToolCallback skillTool = SkillsTool.builder()
                .addSkillsDirectory(tmp.resolve("skills").toString())
                .build();

        // 工具已暴露给模型（definition 非空即通过装配关）
        assertThat(skillTool.getToolDefinition().name()).isNotBlank();

        // 按名加载技能：SkillsInput 的字段是 command
        String output = skillTool.call("{\"command\": \"expense-policy\"}");
        assertThat(output).contains("expense-policy");
        assertThat(output).contains("5 个工作日");
    }

    // ---------- 3) 零样板 Advisor：挂进 ChatClient 走全链路 ----------

    @Test
    void autoMemoryToolsAdvisorRunsInChatClientPipeline() {
        ChatModel model = mockChatModel();
        AutoMemoryToolsAdvisor advisor = AutoMemoryToolsAdvisor.builder()
                .memoriesRootDirectory(tmp.resolve("memories").toString())
                .build();

        ChatClient client = ChatClient.builder(model).defaultAdvisors(advisor).build();
        ChatResponse response = client.prompt().user("你好").call().chatResponse();

        // 请求穿过 Advisor（记忆工具+系统提示注入）并拿到 mock 回答 = 2.0.0 管线兼容
        assertThat(response).isNotNull();
        assertThat(response.getResult().getOutput().getText()).isEqualTo("ok");
    }

    // ---------- 4) 子代理：注册与工具暴露（不真跑模型循环） ----------

    @Test
    void taskToolRegistersClaudeSubagentsAndExposesTool() {
        ChatModel model = mockChatModel();
        // ClaudeSubagentType.builder().build() 返回的是 SPI 接口 SubagentType
        SubagentType type = ClaudeSubagentType.builder()
                .chatClientBuilder("default", ChatClient.builder(model))
                .build();

        ToolCallback taskTool = TaskTool.builder().subagentTypes(type).build();

        assertThat(taskTool.getToolDefinition()).isNotNull();
        assertThat(taskTool.getToolDefinition().name()).isNotBlank();
    }
}
