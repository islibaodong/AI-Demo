package com.example.demo.lesson10_mcp;

import java.time.Duration;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 10 课单元测试：不调用任何大模型，但会拉起真实的 MCP 子进程——
 * 用 MCP Java SDK 经 stdio 与 <code>mcp-server/demo_mcp_server.py</code> 完整走一遍
 * 「initialize 握手 → tools/list 发现工具 → tools/call 执行工具」的协议流程。
 * 只要求本机有 python3，不需要 API Key、不需要网络。
 */
class Lesson10McpTest {

    /** 指向工程内的迷你 MCP 服务器（工作目录 = spring-ai-demo 根，与运行时一致） */
    private static final McpSyncClient CLIENT = McpClient.sync(new StdioClientTransport(
            ServerParameters.builder("python3")
                    .args("mcp-server/demo_mcp_server.py")
                    .env(Map.of("PYTHONUNBUFFERED", "1"))
                    .build(),
            new JacksonMcpJsonMapperSupplier().get()))
            .requestTimeout(Duration.ofSeconds(20))
            .initializationTimeout(Duration.ofSeconds(20))
            .clientInfo(new McpSchema.Implementation("lesson10-test", "1.0.0"))
            .build();

    @AfterAll
    static void close() {
        CLIENT.close();
    }

    @Test
    void handshakeAndListTools() {
        McpSchema.InitializeResult init = CLIENT.initialize();
        assertThat(init.serverInfo().name()).isEqualTo("demo-mcp-server");

        McpSchema.ListToolsResult tools = CLIENT.listTools();
        assertThat(tools.tools())
                .extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("get_current_time", "query_weather");

        // 工具的参数 schema 应声明了 city 参数（模型据此知道传什么）
        McpSchema.Tool weather = tools.tools().stream()
                .filter(t -> t.name().equals("query_weather")).findFirst().orElseThrow();
        assertThat(weather.inputSchema().toString()).contains("city");
    }

    @Test
    void callWeatherTool() {
        CLIENT.initialize();

        McpSchema.CallToolResult result = CLIENT.callTool(new McpSchema.CallToolRequest(
                "query_weather", Map.of("city", "北京")));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(1);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("北京");
    }

    @Test
    void callUnknownToolReturnsProtocolError() {
        CLIENT.initialize();

        // 服务器按协议把错误包在 result.isError 里返回，而不是让通道崩掉
        McpSchema.CallToolResult result = CLIENT.callTool(
                new McpSchema.CallToolRequest("no_such_tool", Map.of()));

        assertThat(result.isError()).isTrue();
    }
}
