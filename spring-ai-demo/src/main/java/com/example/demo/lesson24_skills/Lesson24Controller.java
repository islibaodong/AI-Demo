package com.example.demo.lesson24_skills;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.utils.Skills;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 24 课：Agent 技能（Skills）—— 给 Agent 装"可装卸的知识模块"。
 *
 * <p>工具（lesson05/10）给 Agent <b>能力</b>（能查库、能付款），技能给 Agent
 * <b>知识</b>（懂报销政策、会写会议纪要）。技能是一个文件夹：{@code SKILL.md}
 * （YAML frontmatter 声明 name/description + Markdown 正文），可选附带
 * reference/脚本资源——这是 Anthropic 的 Agent Skills 开放标准，
 * Java 侧由 {@link SkillsTool} 实现（spring-ai-agent-utils）。</p>
 *
 * <p><b>渐进披露（progressive disclosure）三级加载</b>——技能不撑爆上下文的关键：</p>
 * <ol>
 *   <li><b>一级</b>：平时模型只看到每个技能的 name + description（几百 token），
 *       据此判断"该不该用"；</li>
 *   <li><b>二级</b>：模型调用技能工具后，SKILL.md 正文才展开进上下文；</li>
 *   <li><b>三级</b>：正文里提到的 reference.md 等资源，仅在需要时再读
 *       （本课的 expense-policy 技能带了 reference.md 演示）。</li>
 * </ol>
 *
 * <p>对照：RAG（lesson06/17）是"检索进提示词"的知识注入——每次都要检索、
 * 分数不可控；技能是"模型自知的确定性装载"——discovery 靠一级元数据、
 * 装载靠模型主动调用，零检索成本。两者互补：技能放稳定的工作方法，
 * RAG 放海量的事实。</p>
 *
 * <p>试试：</p>
 * <pre>
 * curl "localhost:8080/lesson24/skills"                     # 一级披露：模型平时看到什么
 * curl "localhost:8080/lesson24/chat?q=出差住上海，住宿能报多少"   # 模型应调用 expense-policy 技能
 * curl "localhost:8080/lesson24/chat?q=把这段聊天整理成纪要"      # 模型应调用 meeting-notes 技能
 * </pre>
 */
@RestController
public class Lesson24Controller {

    /** 技能目录（工作目录相对路径；每个子目录一个技能，内含 SKILL.md） */
    static final String SKILLS_DIR = "src/main/resources/skills";

    private final ChatClient client;
    private final ToolCallback skillsTool;

    public Lesson24Controller(ChatModel chatModel) {
        // SkillsTool 是"单工具多技能"：模型只看到一个 skill 工具，
        // 一级元数据（全部技能的 name/description）编排在工具描述里
        this.skillsTool = SkillsTool.builder()
                .addSkillsDirectory(SKILLS_DIR)
                .build();
        this.client = ChatClient.builder(chatModel)
                .defaultToolCallbacks(skillsTool)
                .build();
    }

    // ---------- 一级披露：技能清单 ----------

    /**
     * 列出已发现的技能。注意这就是模型平时"看到"的全部——
     * 只有 name/description，没有正文（对比 /chat 返回里被展开的二级正文）。
     */
    @GetMapping("/lesson24/skills")
    public Map<String, Object> skills() {
        List<org.springaicommunity.agent.tools.SkillsTool.Skill> discovered =
                Skills.loadDirectory(SKILLS_DIR);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("level1", "以下元数据常驻模型上下文（每个技能一行）；正文与 reference 资源按需加载");
        result.put("skills", discovered.stream()
                .map(s -> Map.of(
                        "name", s.name(),
                        "basePath", s.basePath()))
                .toList());
        result.put("toolDescription", skillsTool.getToolDefinition().description());
        return result;
    }

    // ---------- 对话：模型自主决定装载哪个技能 ----------

    /** 带技能的对话：模型看一级元数据自主决定调用 skill 工具装载哪个技能 */
    @GetMapping("/lesson24/chat")
    public Map<String, Object> chat(
            @RequestParam(defaultValue = "出差住上海，住宿能报销多少？") String q) {

        String answer = client.prompt()
                .system("你是企业助理。回答前检查可用的技能（skill），能用到就先调用它装载专业知识再回答。")
                .user(q)
                .call()
                .content();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("availableSkills", Skills.loadDirectory(SKILLS_DIR).stream()
                .map(org.springaicommunity.agent.tools.SkillsTool.Skill::name)
                .toList());
        return result;
    }
}
