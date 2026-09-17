package com.example.demo.lesson23_memory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springaicommunity.agent.tools.AutoMemoryTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 23 课：Agent 长期记忆 —— 让 Agent 记住"值得永远保留的事实"，而不只是本轮对话。
 *
 * <p>lesson04/09 的记忆是<b>会话窗口</b>（ChatMemory）：自动存下每一轮、容量有限、
 * 窗口满了挤掉最旧的——它记住的是"最近聊了什么"。本课的<b>长期记忆</b>是另一层：
 * 模型自己决定"什么值得永远记住"（用户偏好、项目决定、行为纠正），写成
 * Markdown 文件落盘，跨会话、跨窗口地活着。两者互补（生产 Agent 两层都要）：</p>
 *
 * <table border="1">
 *   <tr><th></th><th>ChatMemory（lesson04/09）</th><th>长期记忆（本课）</th></tr>
 *   <tr><td>记什么</td><td>全部对话轮次（自动）</td><td>值得永久保留的事实（模型策展）</td></tr>
 *   <tr><td>生命周期</td><td>滑动窗口内</td><td>无限期（文件）</td></tr>
 *   <tr><td>类比</td><td>工作记忆 / 对话草稿</td><td>笔记本 / 用户档案</td></tr>
 * </table>
 *
 * <p><b>实现</b>：{@link AutoMemoryToolsAdvisor} 一行挂上（零样板）——它把
 * {@link AutoMemoryTools}（6 个沙箱文件操作：View/Create/StrReplace/Insert/Delete/Rename）
 * 和配套系统提示注入请求管线。设计移植自 Claude Code 的 auto-memory，行业对照：
 * Mem0（检索增强）、Letta/MemGPT（分层虚拟上下文）、Zep（时序知识图谱）。</p>
 *
 * <p><b>记忆模型</b>（四类，只存"信号"不存"噪音"）：</p>
 * <ul>
 *   <li>{@code user} — 用户是谁：角色、目标、偏好</li>
 *   <li>{@code feedback} — 怎么干活：纠正与确认过的工作方式</li>
 *   <li>{@code project} — 项目决定与截止日期（代码/git 里查不到的）</li>
 *   <li>{@code reference} — 外部系统指针（看板、仪表盘、频道）</li>
 * </ul>
 *
 * <p><b>存储结构</b>：{@code MEMORY.md} 永远在场的索引（每条记忆一行指针，
 * 模型会话开始先读它）+ 按主题一个 Markdown 文件（YAML frontmatter 声明 name/
 * description/type）——需要哪条才加载哪个文件，上下文窗口不随记忆增长膨胀。</p>
 *
 * <p>试试（跨会话验证——第二个请求是新会话，答案只能来自文件记忆）：</p>
 * <pre>
 * curl "localhost:8080/lesson23/chat?q=请记住：我叫小明，是后端工程师，喜欢简洁的回复"
 * curl "localhost:8080/lesson23/memory"                          # 看落盘的记忆文件
 * curl "localhost:8080/lesson23/chat?q=我喜欢什么样的回复风格？"     # 换个 session 也能答出
 * </pre>
 */
@RestController
public class Lesson23Controller {

    /** 记忆根目录（工作目录相对路径；data/ 已 gitignore，删掉即清空记忆） */
    static final String MEMORIES_ROOT = "data/memories";

    private final ChatClient client;
    /** 只读检查器：给 /lesson23/memory 端点翻记忆目录用（与 Advisor 内部用的是同一套工具） */
    private final AutoMemoryTools memoryInspector;

    public Lesson23Controller(ChatModel chatModel) {
        this.client = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        // 一行挂上长期记忆：内部注入 6 个记忆工具 + 记忆系统提示
                        AutoMemoryToolsAdvisor.builder()
                                .memoriesRootDirectory(MEMORIES_ROOT)
                                .build())
                .build();
        this.memoryInspector = AutoMemoryTools.builder().memoriesDir(MEMORIES_ROOT).build();
    }

    // ---------- 对话：模型自主决定读/写记忆 ----------

    /**
     * 带长期记忆的对话。注意观察：第一次告诉它偏好时，模型会调用 MemoryCreate
     * 落盘 + MemoryInsert 更新索引；之后<b>任何会话</b>问相关问题，它都会先读
     * MEMORY.md 再加载对应文件——记忆来自文件，不来自本轮对话窗口。
     */
    @GetMapping("/lesson23/chat")
    public Map<String, Object> chat(
            @RequestParam(defaultValue = "请记住：我叫小明，是后端工程师，喜欢简洁的回复") String q) {

        String answer = client.prompt().user(q).call().content();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("memoriesRoot", Path.of(MEMORIES_ROOT).toAbsolutePath());
        result.put("memoryIndex", memoryInspector.memoryView("MEMORY.md", null));
        result.put("note", "长期记忆跨会话生效：换个 session 或重启应用，这些事实仍然可查。"
                + "对照 ChatMemory（lesson04/09）：窗口内自动记录每轮，与长期记忆互补。");
        return result;
    }

    // ---------- 记忆目录巡检 ----------

    /** 看模型往长期记忆里写了什么（索引 + 文件列表），验证"跨会话可查" */
    @GetMapping("/lesson23/memory")
    public Map<String, Object> memory() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", Path.of(MEMORIES_ROOT).toAbsolutePath());
        result.put("index", memoryInspector.memoryView("MEMORY.md", null));
        result.put("directory", memoryInspector.memoryView("/", null));
        return result;
    }
}
