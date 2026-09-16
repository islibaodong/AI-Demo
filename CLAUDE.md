# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 本机环境前提（最容易踩的坑）

本机默认 `java` 是 **JDK 8**，而本工程需要 **Java 21**。任何 Maven 命令前都必须先切：

```bash
export JAVA_HOME=/Users/islibaodong/Library/Java/JavaVirtualMachines/ms-21.0.9/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
```

Maven 跟随 `JAVA_HOME`，不切会直接编译失败。shell 状态不跨命令持久化，所以每次调用都要带上。

## 常用命令

```bash
cd spring-ai-demo

# 构建 + 跑全部测试（10 个测试类 / 24 个用例，全部离线，不需要 API Key）
export JAVA_HOME=/Users/islibaodong/Library/Java/JavaVirtualMachines/ms-21.0.9/Contents/Home
mvn clean package

# 只跑单个测试类
mvn test -Dtest=RagChunkingTest
mvn test -Dtest=RagChunkingTest,ConfigBindingTest   # 多个

# 只跑某个包（Surefire 3 匹配的是类名，不是路径：
#   -Dtest='lesson06_rag.*'     ✗ 报 No tests matching pattern
#   -Dtest='lesson06_rag.*Test' ✓ 需带上类名部分）
mvn test -Dtest='lesson06_rag.*Test'

# 启动应用（浏览器打开 http://localhost:8080/ 有 demo 首页）
export OPENAI_API_KEY=sk-...
mvn spring-boot:run
```

依赖已缓存时加 `-o` 走离线模式，可避开本机偶发的 Maven 下载握手失败。

## 版本矩阵（改动前必读）

三者是**强耦合**的，不能单独升级：

| 组件 | 版本 | 约束来源 |
|------|------|----------|
| Spring AI | 2.0.0 | `spring-ai-bom`，在 `<dependencyManagement>` 里统一管理所有 `org.springframework.ai:*` |
| Spring Boot | **4.1.0** | Spring AI 2.0.0 的 starter POM 依赖 `spring-boot-starter-webclient:4.1.0`，父工程必须是 4.1.x，**不能用 3.5.x** |
| Java | 21 | `<java.version>` |

换 Boot 版本前先去看 Spring AI 对应版本 starter 的 POM，别凭印象改。Boot 4.1 相对 3.x 有包位置变动（例如 `@AutoConfigureMockMvc` 已不在 `spring-boot-test-autoconfigure` 中），写测试时注意。

## 架构

### 每个 lesson 是一个自包含的 package

`src/main/java/com/example/demo/lessonNN_xxx/`，每课一个 `@RestController`，聚焦一个概念，互相不依赖。课程顺序即学习顺序：最简调用 → 提示词/结构化输出 → 流式 → 记忆 → 函数调用 → RAG → 图像 → Advisor → 持久化 → MCP → 多模态。

**贯穿全工程的模式**：各 Controller 都注入自动配置好的 `ChatClient.Builder`，再按本课需要定制后 `.build()` 出自己的 `ChatClient` 实例——

- lesson01/02/03/06：`builder.build()`，朴素用法
- lesson04：`builder.defaultAdvisors(MessageChatMemoryAdvisor...)`，挂记忆
- lesson05：`builder.defaultTools(appTools)`，挂工具
- lesson08/09/10：用 `ChatClient.builder(chatModel)` 静态工厂另开 Builder（见下）

所以新增课程时，**不要去定义新的 `ChatClient` Bean**，而是照这个模式从注入的 Builder 派生。

几个实测结论（lesson08/09 相关）：

- **自动配置的 `ChatClient.Builder` 是 `@Scope("prototype")`**（`ChatClientAutoConfiguration` 字节码确认）：每个注入点拿到的都是全新 Builder，跨 Controller 不会互相污染。
- **但对同一个 Builder 变量连续调用 `defaultAdvisors(...)` / `defaultTools(...)` 是叠加不是替换**（字节码里是 `List.addAll`）。需要不同组合时各开一个 Builder（lesson08/09 的做法）。
- **`QuestionAnswerAdvisor` 在 2.0 的 artifact 改名了**：1.x 叫 `spring-ai-advisors-vector-store`（已不在 2.0 BOM 里，引旧名会报 "version is missing"），2.0 是 `spring-ai-vector-store-advisor`，类在 `org.springframework.ai.chat.client.advisor.vectorstore` 包。检索到的文档在 advisor context 里，key 是 `QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS`（`"qa_retrieved_documents"`，值为 `List<Document>`）。

### Advisor 是核心扩展点

`Advisor` 是请求/响应拦截器，记忆（lesson04）、RAG（可用 `QuestionAnswerAdvisor` 替代手写）都靠它。lesson04 里有个必须知道的事实：会话 id 的 param key 是**字面量字符串** `"chat_memory_conversation_id"`，框架没有导出公开常量，写错会静默退化成所有会话共享同一份记忆。

### RAG 是两段式的，不是启动时自动灌库

`RagConfig` 定义 `VectorStore` Bean（`SimpleVectorStore` 内存实现）和 `loadKnowledgeDocuments()` 切块工具，但**灌库由 `GET /lesson6/ingest` 按需触发**，问答走 `GET /lesson6/ask`。lesson09 起 ingest 后会把向量库落盘到 `data/vector-store.json`，启动时若文件存在则自动加载（`SimpleVectorStore.save(File)/load(File)`，不抛受检异常）。

这是刻意的取舍：如果启动时就灌库，没有 API Key 的环境会直接启动失败，整个教学工程就跑不起来了。改这块时保留这个性质。

`Lesson06Controller` 里手写了 Retrieve → Augment → Generate 三步而不是用 `QuestionAnswerAdvisor`，目的是把原理摊开给人看。

### lesson09 的持久化分层

会话记忆通过 `spring-ai-starter-model-chat-memory-repository-jdbc` 落进 H2 文件库（`data/chat-memory.mv.db`）：starter 自动配置 `JdbcChatMemoryRepository` Bean 并按数据库方言执行建表脚本，开关是 `spring.ai.chat.memory.repository.jdbc.initialize-schema`（枚举 `DatabaseInitializationMode`；H2 文件库必须显式 `always`，默认值对文件库不生效）。存储层与对话层解耦：`MessageWindowChatMemory.builder().chatMemoryRepository(repo)` 换存储、API 不变。lesson04 的内存版 `ChatMemory` Bean 与它并存，互不影响。`data/` 已 gitignore，删掉即清空记忆。

**H2 版本被刻意覆盖为 2.5.250**（pom `<h2.version>`）：Boot 4.1 默认管理的 2.4.240 有 CHECK 约束跨会话求值失败的 bug（H2 issue #4302，建表会话关闭后新会话插入必报 23514 "Check constraint invalid"），升级 Boot 后若管理版本 ≥ 2.5.250 可删覆盖。

### lesson10 的 MCP 接入

`spring-ai-starter-mcp-client`（内含 `io.modelcontextprotocol.sdk:mcp-core` + `mcp-json-jackson3`）。接线全在 `application.yml` 的 `spring.ai.mcp.client.stdio.connections.<name>.command/args/env`：启动时拉起 `mcp-server/demo_mcp_server.py` 子进程，自动完成 initialize 握手与 tools/list 发现；`spring.ai.mcp.client.toolcallback.enabled` 默认 true，自动配置会生成 `SyncMcpToolCallbackProvider`（`ToolCallbackProvider` 实现）Bean，Controller 直接注入并用 `.defaultToolCallbacks(provider)` 挂到 ChatClient。工作目录必须是 `spring-ai-demo/`（stdio 脚本路径是相对路径），且本机要有 `python3`——否则启动失败。

实测确认的 API/事实：
- MCP 2.0 SDK 里 `StdioClientTransport` **没有单参数构造器**，需显式传 `McpJsonMapper`：`new StdioClientTransport(params, new JacksonMcpJsonMapperSupplier().get())`（supplier 在 `io.modelcontextprotocol.json.jackson3` 包，mcp-json-jackson3 模块）。
- 客户端构建：`McpClient.sync(transport)...build()` → `McpSyncClient`，方法 `initialize()` / `listTools()` / `callTool(CallToolRequest)`；协议握手时版本协商结果（如 2025-11-25）以服务器回应为准。
- 迷你服务器是**纯 Python 标准库**手写的 JSON-RPC（stdout 按行传协议、日志走 stderr），Lesson10McpTest 用 SDK 真实走协议往返，不联网不调模型。

### lesson11 的多模态

三个独立端点：视觉问答（`UserMessage.builder().text(q).media(Media...)` 挂 `org.springframework.ai.content.Media`，TTS（`OpenAiAudioSpeechModel.call(text)` 直接返回 mp3 `byte[]`）、STT（`TranscriptionModel.call(new AudioTranscriptionPrompt(resource))`，`response.getResults().get(0).getOutput()` **直接返回 String**，没有 `.getText()`）。语音 Bean 由 openai starter 自动装配（`spring.ai.model.audio.speech/transcription` 默认 openai），配置在 `spring.ai.openai.audio.speech.{model,voice}` / `audio.transcription.model`。

实测确认的 API/事实：
- `Media.builder().data(URI)` 存进去的是 **URI 的字符串形式**，取回 `getData()` 得到 `String` 而非 URI；`data(Resource)` 则在发给模型时读成 `byte[]`。
- `OpenAiAudioApi.TranscriptionModel.WHISPER_1` 枚举在 2.0 已不存在（1.x 遗留），构造 `AudioTranscriptionPrompt` 不要传 options。
- OpenAI 按文件名扩展名识别音频格式，上传字节时要用 `ByteArrayResource` 重写 `getFilename()` 返回带扩展名的名字（如 `audio.mpeg`）。
- 视觉探针图 `resources/images/demo-scene.png` 是**程序手绘**的 320x240 PNG（蓝天/草地/右上角红色大圆，struct+zlib 生成，无 PIL 依赖）——问"图里有什么"模型能答出"红色太阳"即证明视觉输入真实生效（与第 6 课创始人探针同款思路），别把它替换成真实照片。

## 约定

**配置文件用 `application.yml`，不要改回 `application.properties`。** 后者规范默认编码是 ISO-8859-1，IntelliJ 据此解析会把 UTF-8 中文注释显示成乱码，且该默认值是 IDE 全局设置、新项目一律继承，工程内改不动。YAML 规范强制 UTF-8。这不是风格偏好。

**API Key 只从环境变量 `OPENAI_API_KEY` 读**（`application.yml` 里写 `${OPENAI_API_KEY:}`），绝不写进代码或提交。

**`.env` 支持与 OpenAI 中转站**：`config/DotEnvEnvironmentPostProcessor` 在启动最早期把工作目录下的 `.env` 注入为属性源，注册在 `src/main/resources/META-INF/spring.factories`。两个容易踩的点：

- Boot 4 起 EPP 接口在 `org.springframework.boot` 包（旧 `org.springframework.boot.env` 包已 @Deprecated），spring.factories 的 key 写错包名**不会报错，只会静默忽略**。
- 优先级是 真实环境变量 > `.env` > application.yml 默认值（EPP 用 `addAfter("systemEnvironment")` 实现）。

另一个实测结论：Spring AI 2.0 底层的 OpenAI 官方 SDK 请求路径 = `base-url` + `/chat/completions`，**不会自动补 `/v1`**，所以 `base-url` 默认值是 `https://api.openai.com/v1`（带 `/v1`）。中转站同理。另外 SDK 对连接级失败（域名不存在）不抛异常，表现为接口返回空 body 的 200。

**测试必须全部离线。** 现有 10 个测试类共 24 个用例（`ConfigBindingTest`、`PromptTemplateTest`、`BeanOutputConverterTest`、`MemoryWindowTest`、`RagChunkingTest`、`DotEnvEnvironmentPostProcessorTest`、`AdvisorTest`、`Lesson09PersistenceTest`、`Lesson10McpTest`、`Lesson11MultimodalTest`）都不联网、不需要 API Key（`Lesson10McpTest` 会拉起 python3 子进程走真实 MCP 协议），覆盖模板渲染、JSON 解析、记忆窗口裁剪、RAG 切块、`.env` 加载、Advisor 行为、JDBC/向量库持久化往返（JDBC 测试用 H2 内存库自建表，向量化用 Mockito 固定向量）、MCP 握手/工具发现/工具调用、多模态消息组装。新增测试请保持这个性质——没有 Key 的人也要能 `mvn test` 全绿。

**注释用中文，并标注对应的 LangChain 概念。** 这个工程的目标读者是从 Python LangChain 转过来的人（注意：LangChain 是 Python 生态的，Java 没有官方对应物，本工程教的就是 Spring AI 本身）。

## Spring AI 2.0 API 的实际签名

写代码前建议用 `javap` 对着本地 jar 确认，以下几条是凭印象容易写错的：

| 想当然的写法 | 2.0 实际 |
|---|---|
| `MessageWindowChatMemory.builder().windowSize(n)` | `.maxMessages(n)` |
| `Document.getContent()` | `Document.getText()` |
| `ChatMemoryAdvisor` | 仍叫 `MessageChatMemoryAdvisor` |
| `SearchRequest.builder()` 后直接 `.build()` | 需 `.query(q).topK(k).build()` |
| `SimpleVectorStore.load(File)` 要 catch IOException | 不抛受检异常（save/load 均不抛） |
| `JdbcChatMemoryRepository` 手动 new | 用 starter 自动配置的 Bean，或 `builder().jdbcTemplate(...)`/`dataSource(...)`（dialect 可省，按数据源 URL 推断） |
| `Media.builder().data(URI)` 取回 URI | `getData()` 返回 **String**（URI 的字符串形式）；`data(Resource)` 发送时读成 byte[] |
| `AudioTranscription...getOutput().getText()` | `getOutput()` **直接返回 String**，无 getText() |
| `OpenAiAudioApi.TranscriptionModel.WHISPER_1` | 2.0 已删除该枚举；`new AudioTranscriptionPrompt(resource)` 不传 options |
| `new StdioClientTransport(params)` | 2.0 SDK 需两参：`(params, new JacksonMcpJsonMapperSupplier().get())` |
| `McpClient.sync(transport).build()` 后直接用 | 需先 `initialize()` 再 `listTools()`/`callTool(...)` |

## 第 6 课的验证技巧

`src/main/resources/docs/spring-ai-knowledge.md` 里的「Spring AI 创始人」信息是**故意编造的**，不在任何模型的预训练数据里。如果 `/lesson6/ask?q=Spring AI 的创始人是谁` 能答对，就证明检索真的生效了。改知识库时别把这条"修正"成真实信息，否则就失去了这个探针的意义。
