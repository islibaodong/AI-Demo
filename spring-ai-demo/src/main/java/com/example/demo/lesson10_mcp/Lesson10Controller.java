package com.example.demo.lesson10_mcp;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 10 课：MCP（Model Context Protocol）—— 把外部系统以标准协议接入模型。
 *
 * <p>第 5 课的 @Tool 方法写在<b>本进程内</b>。MCP 解决的是另一类问题：
 * 工具由<b>独立的进程/服务</b>提供，通过标准协议暴露，谁都能接入——
 * 这相当于工具生态的 USB-C：写一次 MCP 服务器，Claude、Cursor、Spring AI
 * 等任何 MCP 客户端都能直接用它的工具，不必各自写集成代码。</p>
 *
 * <p>本课的接线全部在配置里（application.yml 的 spring.ai.mcp.client）：</p>
 * <ol>
 *   <li>启动时按 stdio 配置拉起子进程 <code>mcp-server/demo_mcp_server.py</code>；</li>
 *   <li>框架自动完成 initialize 握手 + tools/list 工具发现；</li>
 *   <li>自动配置生成 {@link ToolCallbackProvider} Bean（SyncMcpToolCallbackProvider）；</li>
 *   <li>本类只做一件事：把它挂到 ChatClient 上。之后模型看到 weather/time 工具，
 *       决定调用 → 框架经协议转发给 Python 子进程执行 → 结果回喂模型。</li>
 * </ol>
 *
 * <p><b>与第 5 课对比</b>：@Tool ≈ 本地方法调用；MCP ≈ 标准协议的进程间/网络调用。
 * 业务代码（ChatClient 那几行）完全一样——这就是抽象一致的价值。</p>
 *
 * <p><b>与 LangChain 对照</b>：MCP 客户端 ≈ LangChain 的
 * <code>langchain-mcp-adapters</code>（MultiServerMCPClient），工具消费方式同为
 * "发现工具 → 转成框架的工具协议 → bind 给模型"。</p>
 *
 * <p>试试：</p>
 * <ul>
 *   <li><code>curl "localhost:8080/lesson10/tools"</code> —— 看看 MCP 服务器注册了哪些工具</li>
 *   <li><code>curl "localhost:8080/lesson10/chat?q=北京天气怎么样"</code> —— 模型调用 Python 侧的 query_weather</li>
 *   <li><code>curl "localhost:8080/lesson10/chat?q=现在几点了"</code> —— 调用 get_current_time</li>
 * </ul>
 */
@RestController
public class Lesson10Controller {

    private final ChatClient chatClient;
    private final ToolCallbackProvider mcpToolProvider;

    public Lesson10Controller(ChatModel chatModel, ToolCallbackProvider mcpToolProvider) {
        this.mcpToolProvider = mcpToolProvider;
        // 用 ChatModel 开新 Builder（与第 8/9 课同款，避免与别的客户端混用配置）
        this.chatClient = ChatClient.builder(chatModel)
                .defaultToolCallbacks(mcpToolProvider)   // MCP 工具以 Provider 形式挂载
                .build();
    }

    /** 查看 MCP 服务器都注册了哪些工具（启动时已通过 tools/list 发现完毕） */
    @GetMapping("/lesson10/tools")
    public List<Map<String, String>> tools() {
        return java.util.Arrays.stream(mcpToolProvider.getToolCallbacks())
                .map(this::describe)
                .toList();
    }

    private Map<String, String> describe(ToolCallback cb) {
        return Map.of(
                "name", cb.getToolDefinition().name(),
                "description", String.valueOf(cb.getToolDefinition().description()));
    }

    /** 提问即用：模型自主决定是否调用 MCP 工具（天气/时间），工具在 Python 子进程里执行 */
    @GetMapping("/lesson10/chat")
    public String chat(@RequestParam String q) {
        return chatClient.prompt().user(q).call().content();
    }
}
