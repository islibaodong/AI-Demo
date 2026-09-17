package com.example.demo.lesson25_subagents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 25 课：子代理编排（多智能体）—— 让专业化的子代理各自在<b>独立上下文窗口</b>里干活。
 *
 * <p>lesson16/22 的 Agent 是"一个模型 + 一堆工具"的单体；任务一复杂，
 * 全部中间过程都挤在同一上下文里（工具输出、中间结论、失败尝试……），
 * 上下文越堆越脏，质量随之衰减（context rot）。子代理编排的解法是<b>分而治之</b>：</p>
 *
 * <ul>
 *   <li><b>主代理（编排者）</b>：只面对用户，手里多一个 Task 工具；
 *       通过 Agent Registry（子代理清单）知道"有哪些专家、各擅长什么"；</li>
 *   <li><b>子代理</b>：每个是一个独立 ChatClient——<b>独立上下文窗口</b>、
 *       独立系统提示、可配不同工具/不同模型（多模型路由：杂活用便宜模型，
 *       攻坚用旗舰模型）；</li>
 *   <li><b>结果回流</b>：子代理只把最终结论交回主代理，中间过程全部留在
 *       子代理自己的窗口里——主代理的上下文保持干净。</li>
 * </ul>
 *
 * <p><b>声明式定义</b>：子代理就是 {@code agents/*.md} 文件
 * （frontmatter 声明 name/description，正文是系统提示）——启动时装载进
 * Agent Registry，加一个新专家 = 加一个 markdown 文件，不改代码。</p>
 *
 * <p><b>与已有课程的分工</b>：lesson21 的图编排是<b>业务画流程</b>（节点/边/审批固定）；
 * 本课是<b>模型做分派</b>（主代理看任务描述自主决定派给谁）。
 * A2A 协议（spring-ai-agent-utils-a2a 模块）把这个模式扩展到<b>跨进程/跨组织</b>：
 * 子代理可以是远端 HTTP 服务（这里从简，用本地子代理讲透机制）。</p>
 *
 * <p>试试：</p>
 * <pre>
 * curl "localhost:8080/lesson25/agents"    # Agent Registry：启动时装载的子代理清单
 * curl "localhost:8080/lesson25/chat?q=调研一下报销政策里住宿标准的要点，再写成给客户的正式说明"
 * </pre>
 */
@RestController
public class Lesson25Controller {

    /** 子代理定义目录（每个 .md 文件 = 一个专家；工作目录相对路径） */
    static final String AGENTS_DIR = "src/main/resources/agents";

    private final ChatModel chatModel;
    private final ChatClient mainClient;
    /** 与 mainClient 共用 TaskTool 的注册表，供 /agents 端点展示 */
    private final List<SubagentInfo> registry = new ArrayList<>();

    /** Agent Registry 的条目（从 agents/*.md 的 frontmatter 解析） */
    public record SubagentInfo(String name, String description, String sourceFile) {
    }

    public Lesson25Controller(ChatModel chatModel) {
        this.chatModel = chatModel;

        // Agent Registry：启动时解析 agents/*.md（加专家=加文件，不改代码）
        this.registry.addAll(loadRegistry());

        // Task 工具：subagentType 决定"子代理怎么执行"（这里用 Claude 型——
        // markdown 定义 + 独立 ChatClient）；chatClientBuilder 是子代理的模型来源，
        // 生产上可给不同 subagent 配不同模型（多模型路由）
        ToolCallback taskTool = TaskTool.builder()
                .subagentTypes(ClaudeSubagentType.builder()
                        .chatClientBuilder("default", ChatClient.builder(chatModel))
                        .build())
                .subagentReferences(ClaudeSubagentReferences.fromRootDirectory(AGENTS_DIR))
                .build();

        this.mainClient = ChatClient.builder(chatModel)
                .defaultToolCallbacks(taskTool)
                .build();
    }

    // ---------- Agent Registry ----------

    @GetMapping("/lesson25/agents")
    public Map<String, Object> agents() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("note", "启动时从 " + AGENTS_DIR + " 装载；加一个 .md 文件 = 加一个专家，不改代码");
        result.put("subagents", registry.stream()
                .map(s -> Map.of("name", s.name(), "description", s.description(),
                        "source", s.sourceFile()))
                .toList());
        return result;
    }

    // ---------- 主代理对话：模型自主分派 ----------

    /** 主代理面对用户，看 Registry 的 description 自主决定派给哪个子代理 */
    @GetMapping("/lesson25/chat")
    public Map<String, Object> chat(
            @RequestParam(defaultValue = "调研一下报销政策里住宿标准的要点，再写成给客户的正式说明") String q) {

        String answer = mainClient.prompt()
                .system("""
                        你是主代理（编排者）。你有一个 Task 工具可以委派子代理，各子代理的专长见工具说明。
                        复合任务请拆解后委派给合适的子代理（可多个、按序），自己只做拆解与汇总。
                        汇总时直接给用户最终结果，不要暴露委派过程。""")
                .user(q)
                .call()
                .content();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("registry", registry.stream().map(SubagentInfo::name).toList());
        result.put("note", "子代理在独立上下文窗口里工作，中间过程不会挤进主代理的上下文。");
        return result;
    }

    // ---------- frontmatter 解析（Registry 展示用；TaskTool 内部有自己的解析器） ----------

    private static final Pattern NAME_PATTERN = Pattern.compile("(?m)^name:\\s*(.+)$");
    private static final Pattern DESC_PATTERN = Pattern.compile("(?m)^description:\\s*(.+)$");

    /** 读 agents/*.md 的 frontmatter，组装 Registry 展示数据 */
    static List<SubagentInfo> loadRegistry() {
        List<SubagentInfo> infos = new ArrayList<>();
        try (var files = Files.list(Path.of(AGENTS_DIR))) {
            files.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            Matcher name = NAME_PATTERN.matcher(content);
                            Matcher desc = DESC_PATTERN.matcher(content);
                            infos.add(new SubagentInfo(
                                    name.find() ? name.group(1).trim() : p.getFileName().toString(),
                                    desc.find() ? desc.group(1).trim() : "",
                                    p.getFileName().toString()));
                        }
                        catch (Exception ignored) {
                            // 单个文件坏了不拖垮启动
                        }
                    });
        }
        catch (Exception ignored) {
            // 目录不存在时 Registry 为空
        }
        return infos;
    }
}
