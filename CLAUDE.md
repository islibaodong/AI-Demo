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

# 构建 + 跑全部测试（18 个测试类 / 86 个用例，全部离线，不需要 API Key）
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

`src/main/java/com/example/demo/lessonNN_xxx/`，每课一个 `@RestController`，聚焦一个概念，互相不依赖。课程顺序即学习顺序：最简调用 → 提示词/结构化输出 → 流式 → 记忆 → 函数调用 → RAG → 图像 → Advisor → 持久化 → MCP → 多模态 → 健壮性 → 安全防护 → 可观测与成本 → 结构化输出修复 → Agent 编排 → RAG 业务进阶 → 评估与回归 → 结课 Capstone（整链组装）。

**贯穿全工程的模式**：各 Controller 都注入自动配置好的 `ChatClient.Builder`，再按本课需要定制后 `.build()` 出自己的 `ChatClient` 实例——

- lesson01/02/03/06：`builder.build()`，朴素用法
- lesson04：`builder.defaultAdvisors(MessageChatMemoryAdvisor...)`，挂记忆
- lesson05：`builder.defaultTools(appTools)`，挂工具
- lesson08/09/10：用 `ChatClient.builder(chatModel)` 静态工厂另开 Builder（见下）
- lesson12：主 client 用注入的 ChatModel；备用/超时演示模型用 `OpenAiChatModel.builder().openAiClient(new OpenAIClientImpl(...))` 手工组装

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

### lesson12 的健壮性（超时/重试/熔断/降级）

**2.0 关键变化**：OpenAI 底层换成官方 openai-java SDK（openai-java-core 4.39.1，OkHttp），超时与重试内置于 SDK，配置入口是 `spring.ai.openai.timeout`（Duration，默认 60s）与 `spring.ai.openai.max-retries`（默认 3，只对 408/409/429/5xx 指数退避重试，且尊重响应头 `X-Should-Retry`/`Retry-After`）。**1.x 的 `spring.ai.retry.*`（SpringAiRetryAutoConfiguration 的 RetryTemplate）已不参与 OpenAI 模型**，别照旧博客配。

手工组装另一个 ChatModel（降级/超时演示）的四件套：`SpringAiOpenAiHttpClient.builder().timeout(Duration).build()` → `new ClientOptions.Builder().httpClient(...).baseUrl(...).apiKey(...).timeout(...).maxRetries(...).build()` → `new OpenAIClientImpl(options)` → `OpenAiChatModel.builder().openAiClient(client).options(OpenAiChatOptions.builder().model(...).build()).build()`（ClientOptions.Builder 也有 `fromEnv()`，但读的是真实环境变量而非 .env 属性源）。

手写熔断器 `SimpleCircuitBreaker`（CLOSED→OPEN→HALF_OPEN 状态机）只用于讲原理，生产用 Resilience4j。lesson12 的演示依赖假中转站的故障注入标记（RETRY-DEMO 前 2 次 429 / FAIL-DEMO 永远 500 / SLOW-DEMO 睡 3 秒，脚本在 /tmp/fake_relay.py，不在仓库里）。

### lesson13 的安全防护

Prompt 注入靶场（OWASP LLM01）：`SYSTEM_PROMPT` 里埋假口令金丝雀 `SPR-SEC-DEMO-77`（真实系统不存在，输出中出现 = 100% 泄露，探针思路同 lesson06 创始人）。四层纵深防御：① `/lesson13/vulnerable` 反面教材——系统提示与用户输入拼成一条 UserMessage，注入必成功；② `/lesson13/guarded`——SystemMessage/UserMessage 结构隔离 + `PromptInjectionGuardAdvisor`（关键词黑名单短路，挡不住变形攻击）+ 输出扫描；③ `SecretLeakGuard` 输出侧——**一个类同时实现 CallAdvisor（整段扫描）与 StreamAdvisor（滚动窗口逐 chunk 扫，金丝雀切在 chunk 边界也能命中，`takeUntil` 截断流）**；④ `/lesson13/tools`——工具最小权限，`MethodToolCallbackProvider.builder().toolObjects(obj).build().getToolCallbacks()` 拿到全部 ToolCallback 后按 `getToolDefinition().name()` 白名单过滤再 `.toolCallbacks(...)`。

两个必须知道的实现坑（都踩过）：`.formatted()` 优先级低于 `+`（`"a" + "b".formatted(x)` 只格式化第二个字面量，要把拼接整体加括号）；防护器自己的警告文案也不能包含金丝雀值，否则防护器就是泄露源。

### lesson14 的可观测与成本

三层观测体系：① `/lesson14/usage`——`ChatResponse.getMetadata().getUsage()` 拿真实 token 用量（prompt/completion/total 三项），`ModelPricing` 单价表换算成本（**金额用 BigDecimal 不用 double**；completion 单价是 prompt 的 3~4 倍）；② `/lesson14/metrics`——Spring AI 2.0 自带 `ModelUsageMetricsGenerator.generate(usage, context, meterRegistry)`（`org.springframework.ai.model.observation` 包），按 GenAI 语义约定注册 `gen_ai.client.token.usage` 计数器（tag `gen_ai.token.type` = input/output/total，常量在 `AiObservationMetricNames`/`AiTokenType`）；本工程没引 actuator（无全局 MeterRegistry Bean），Controller 自建 `SimpleMeterRegistry` 演示，生产用 actuator 自动接线 + Prometheus 导出；③ `/lesson14/budget`——`TokenBudgetAdvisor` 累计 `getTotalTokens()`，超限短路（请求不出网）；④ `/lesson14/cap`——`OpenAiChatOptions.builder().maxTokens(n)` 给 completion 封顶。

三个实测确认的 API/事实：
- **2.0 的 `ChatClientRequestSpec.options()` 接收的是 Builder 而非成品对象**：`.options(OpenAiChatOptions.builder().maxTokens(n))`，带 `.build()` 会编译失败（1.x 是传成品）。
- `ModelPricing.of()` 这类 contains 模糊匹配模型名时**长名必须排在前面**："gpt-4o-mini" 也 contains "gpt-4o"，先查 4o 会把 mini 误判成 4o（贵 16 倍）——离线测试抓出来的。
- openai-java SDK 发送 output 上限时用的 key 是 `max_completion_tokens`；假中转站两个 key 都处理（脚本在 /tmp/fake_relay.py，不在仓库里；usage 按文本长度模拟，≈4 字符/token、每条消息 +4）。

### lesson15 的结构化输出修复

`StructuredOutputRepairer` 三级修复管道（成本从低到高）：DIRECT 直接解析 → EXTRACT 本地抽取（截第一个 `{` 到最后一个 `}`，专治寒暄包裹，零成本）→ MODEL_REPAIR（坏输出 + 具体解析错误喂回模型）。全部失败返回 `Parsed.FAILED`（不抛异常），调用方自行降级。端点：`/lesson15/naive` 反面教材（裸 `.entity()` 遇坏输出直接 500）与 `/lesson15/repair`（返回带 strategy/attempts，修复可观测）。假中转站标记：CLEAN-JSON / CHATTY-JSON（寒暄包裹）/ TRUNC-JSON（截断）/ FENCE-JSON（围栏），修复轮分支（提示词含「修复」+「JSON」）**必须排在标记分支前**——修复提示词里会引用带标记的坏输出，否则修复轮永远失败。

2.0 `BeanOutputConverter` 行为边界（探针程序实测）：**markdown 围栏已由内置 `MarkdownCodeBlockCleaner` 自动清理**（1.x 需手动剥，2.0 不再是问题）；寒暄包裹抛 `tools.jackson.core.exc.StreamReadException`、截断抛 `UnexpectedEndOfInputException`、字段类型错抛 `tools.jackson.databind.exc.InvalidFormatException`——全部继承 `tools.jackson.core.JacksonException`（unchecked，Jackson 3 换了 `tools.jackson` 包名），管道统一 catch 它。与 LangChain 的 `OutputFixingParser` 同思路，但多了本地抽取这一级。

### lesson16 的多步 Agent 编排

售后工单场景（queryOrder→checkRefundPolicy→applyRefund→createTicket→notifyUser，`AfterSaleTools` 固定假返回）。两个端点：`/lesson16/auto` 框架内置循环（`.toolCallbacks(...)` 黑盒）与 `/lesson16/agent` 治理版（返回完整工具轨迹）。

**2.0 内置循环没有步数上限，也没有 `internalToolExecutionEnabled` 开关**——对全部 2.0 jar 做字符串搜索确认二者都不存在（1.x 的 `spring.ai...internal-tool-execution-enabled` 配置已删）。框架的循环（ToolCallingManager 在 OpenAiChatModel 内部驱动）是黑盒，业务代码拿不到每一步。因此**治理只能做在工具执行层：装饰器**——`GovernedTool implements ToolCallback` 包住真实工具，`getToolDefinition()` 直接委托（模型看到的"菜单"不变），两个 `call()` 重载（带/不带 `ToolContext`）都要转发到治理逻辑（框架带 ToolContext 时走的是另一个重载，只改一个会绕过治理）。

`AgentGovernor` 三个治理动作：① 审计轨迹（`[执行]/[拦截] name(input)` 列表）；② 工具调用预算（全部工具共享计数，超限**不抛异常**，返回"引导收尾"话术——把约束写进工具结果让模型自然停下，抛异常会炸掉整条链路）；③ 审批门（高危工具拦截并返回"需人工审批 + 建议改道 createTicket"，模型读结果自主改道）。与 lesson13 工具白名单的关系：那是**事前**（不给模型看），这是**事中**（给看但执行前拦截）。

假中转站脚本链按 **last assistant tool_call name** 驱动（applyRefund 分支再按工具结果里有无"人工审批"分流），**必须排在通用 `tool_msg` 复述分支前**，否则 agent 第二轮就被复述分支劫持；首步触发**必须判请求带 `tools` 字段**——lesson17 的 RAG 问答文案里也有「售后/退款」字样，不判 tools 会劫持 lesson17 的请求（运行时踩过）。

### lesson17 的 RAG 业务进阶

售后 FAQ 场景（`KnowledgeBase` 结构化知识库：chunk 带 docId/source/chunk 序号 metadata，复合主键 `docId#序号`）。三个端点：`/lesson17/ingest`（增量灌库）、`/lesson17/search`（三路检索对比）、`/lesson17/ask`（拒答+引用溯源）。**自建 SimpleVectorStore，不注入 lesson06 的 Bean**（知识库隔离）；语料列表本课自己持有（关键词路要全量打分）。

关键设计/实测结论：
- **混合检索两路的原始分数不可比**（余弦相似度 vs bigram 覆盖率，量纲不同），融合用 **RRF（只看排名）**：`score = Σ 1/(60+rank)`，两路都靠前的段落胜出。关键词路用**字符 bigram 覆盖率**（`KeywordScorer`，中文零分词依赖，可离线测试）。
- **RRF 截断必须发生在重排之后**（`search(q, 5, 5)` 宽融合候选 → rerank → top3 给生成）——截断在重排前会把"单路强命中"的正确答案（关键词中了、语义没中）挤掉，被"两路都沾边但都不强"的段落顶替（运行时实测踩过）。重排还有过滤作用：**rerankScore=0 的段落不进提示词**。
- **拒答阈值卡在 rerank 分数上**（bigram 覆盖率 0~1，默认 0.25）：低于阈值不调模型直接 `refused=true`。注意阈值语义依赖重排分数而非 RRF 分（RRF 量级 ~0.03 无法直观设阈值）。
- **增量灌库**：docId 是稳定主键，幂等重复灌自动 skip；`?update=invoice` 先 `store.delete(旧 chunk ids)` 再 add v2 内容，未动过的文档不重灌（lesson06 的 ingest 是全量追加，重复调用会新旧混杂）。
- **假中转站的 embeddings 返回常量向量** → 向量检索退化为按插入序，`/lesson17/search` 的 vectorOnly 路第一名是无关段落——这是"embedding 质量差时混合检索救场"的活教材，别去修 relay 让它返回真实向量。
- 离线测试用**手写 BigramEmbedding**（文本 bigram hash 进 64 维向量）驱动真实 SimpleVectorStore，语义即词面、完全可复现；Mockito mock 的 ChatModel 塞进 ChatClient 前必须 stub `getOptions()` 返回 `ChatOptions.builder().build()`（ChatClient.call 内部会 mutate options，返回 null 直接 NPE）。

### lesson18 的评估与回归（Evals）

两个端点：`/lesson18/evals`（规则断言跑批）与 `/lesson18/judge`（LLM 裁判）。核心抽象：**被测系统就是一个 `String -> String` 函数**（`EvalRunner.run(cases, sut)`）——不管内部是 ChatClient/RAG/Agent，评估代码与应用解耦。两条执行原则：单条失败不中断跑批（要一次拿全失败明细）、被测系统抛异常记为该条失败（异常也是回归信号）。

- **内置评估集 = 前几课探针的集合化**：rag-founder-probe（lesson06 编造创始人）、security-injection-probe（lesson13 金丝雀，断言用 `NotContains(CANARY)` 而非 Contains 拦截文案——文案怎么改都不影响评估）、冒烟两条。被测入口复用 lesson13 的 `PromptInjectionGuardAdvisor`（public 可跨包），探针测的就是"防护挂在生产入口上是否真的生效"（测试里 `verify(never())` 证明拦截在模型之前）。
- **LLM-as-Judge**（`LlmJudge`）：开放性回答规则断言写不了，让模型按参考答案打 1-5 分（`.entity(JudgeVerdict.class)`，score>=4 通过）。定位：裁判也会错，**分数的回归趋势可靠、绝对值不做达标线**。
- **假中转站支持**：① `创始人` 分支返回编造答案（模拟 RAG 命中）；② judge 分支（user 消息含 BeanOutputConverter schema 的 `score`+`reason` 字段）返回**干净 JSON 裁决**（`.entity()` 不容忍寒暄包裹），分数按"参考答案 bigram 在实际回答中的覆盖率 ≥0.3 → 4 分，否则 1 分"启发式，两条路都能演示。

### lesson19 的结课 Capstone（mini 智能客服系统）

把前 18 课组装成一条生产链路（每处注明整合自第几课）：`/lesson19/setup` 灌 FAQ 库（复用 lesson17 的 KnowledgeBase/主键约定）→ `/lesson19/support` 主入口 → `/lesson19/support/stream` 流式 → `/lesson19/summary` 工单摘要 → `/lesson19/evals` 结课回归。响应结构是"生产客服该有的样子"：answer / citations（17）/ toolCalls 审计轨迹（16）/ tokens + estimatedCostUsd（14）。

- **输入侧 Advisor 链**：注入拦截（13，order -100）→ 敏感词打码（8）→ token 预算（14，order -50）；输出侧 `SecretLeakGuard`（order 90，Call+Stream 双实现）。会话记忆用 `MessageChatMemoryAdvisor`（4/9）。
- **多路防御的真实行为**：注入探针句的检索分是 0，会被 lesson17 的拒答层在 guard 之前挡下。评估/测试只断言行为不变量（金丝雀不出现 + `verify(never())` 模型从未被调用），不断言"是哪一层拦的"——断言层次会随检索质量漂移。
- **跨包复用**：`Lesson16Controller.GovernedTool` 为此改成 `public static class`（public 构造器），capstone 给售后工具包治理装饰器。每次请求 new 一个 `AgentGovernor`（一次任务的预算与审计边界）。
- **三个 2.0.0 实测坑**：
  1. **`call()` 的 `content()` 与 `chatResponse()` 是两个独立终端操作**，各触发一次完整 Advisor 链 + 模型调用，第二个还会抛 "No CallAdvisors available to execute"。只能调一个，从 `chatResponse()` 里同时取 `.getResult().getOutput().getText()` 与 `getMetadata()`（usage/model）。
  2. **stream 路径不能挂 `MessageChatMemoryAdvisor`**：`after()` 在聚合回调里从聚合响应读会话 id，而响应 chunk 不携带 advisor params（call 路径的最终响应会显式带上，所以 call 正常）→ 必抛 "conversationId cannot be null"。流式端点用无记忆 Advisor 的专用 client（流式回复不写历史）。底层原因：`ChatClientMessageAggregator` 是逐 chunk `putAll` 累积 context，chunk 里没有就是空。
  3. mock ChatModel 必须 stub `getOptions()` 返回 `ChatOptions.builder().build()`，否则 ChatClient 内部 `mutate` NPE（lesson17 同款）。
- **假中转站支持**（三条件缺一不可，注意分支顺序）：Agent 链按 last assistant tool_call 驱动，**首步条件必须是 `带 tools 且无 tool_call 且含「订单」`**（lesson19 的 RAG 文案里也有「退款」，条件宽了会劫持纯问答）；capstone 资料分支 `system 含「依据下面的资料」且 last_text 含「退款」且无 tool_call 且非 stream` → 返回 "退款 3-5 个工作日原路退回 [1]。"——**必须排除已有 tool_call 的会话**（capstone 的客服请求也带 tools，否则会把 Agent 第二轮劫持成纯文本）**且排除 stream 请求**（SSE 要放行给流式分支）。
- **摘要端点的策略回包**：`parsed.strategy().name()` 是 String（EXTRACT 等），不是枚举本身。

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

**测试必须全部离线。** 现有 18 个测试类共 86 个用例（`ConfigBindingTest`、`PromptTemplateTest`、`BeanOutputConverterTest`、`MemoryWindowTest`、`RagChunkingTest`、`DotEnvEnvironmentPostProcessorTest`、`AdvisorTest`、`Lesson09PersistenceTest`、`Lesson10McpTest`、`Lesson11MultimodalTest`、`Lesson12RobustnessTest`、`Lesson13SecurityTest`、`Lesson14ObservabilityTest`、`Lesson15StructuredOutputTest`、`Lesson16AgentTest`、`Lesson17RagAdvancedTest`、`Lesson18EvalsTest`、`Lesson19CapstoneTest`）都不联网、不需要 API Key（`Lesson10McpTest` 会拉起 python3 子进程走真实 MCP 协议），覆盖模板渲染、JSON 解析、记忆窗口裁剪、RAG 切块、`.env` 加载、Advisor 行为、JDBC/向量库持久化往返（JDBC 测试用 H2 内存库自建表，向量化用 Mockito 固定向量）、MCP 握手/工具发现/工具调用、多模态消息组装、成本估算/内置指标/预算防护、解析失败探针/三级修复管道、治理装饰器（审批门/预算/审计）、混合检索/RRF/重排/增量灌库/拒答、评估跑批/LLM 裁判、Capstone 整链集成（bigram 假 embedding + 真实 Advisor 链：打码进模型前/注入不泄露/拒答不调模型/工单 JSON 解析）。新增测试请保持这个性质——没有 Key 的人也要能 `mvn test` 全绿。

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
| `spring.ai.retry.*` 配置 OpenAI 重试 | 2.0 无效；用 `spring.ai.openai.max-retries`/`timeout`（官方 SDK 内置） |
| 1.x 那样配 RestClient 超时 | 2.0 底层是 OkHttp（openai-java），`SpringAiOpenAiHttpClient` + `ClientOptions` |
| `McpClient.sync(transport).build()` 后直接用 | 需先 `initialize()` 再 `listTools()`/`callTool(...)` |
| 1.x 那样配 `internalToolExecutionEnabled` 限制 Agent 步数 | 2.0 无此开关也无步数上限（全量 jar 字符串搜索确认）；治理用装饰器包 `ToolCallback`，且 `call(String)` 与 `call(String, ToolContext)` 两个重载都要转发 |
| `ChatClient...options(OpenAiChatOptions)` 传成品对象 | 2.0 传 **Builder**：`.options(OpenAiChatOptions.builder().maxTokens(n))` |
| JSON 解析异常 catch `com.fasterxml...JsonProcessingException` | Jackson 3 换包为 `tools.jackson`，统一 catch `tools.jackson.core.JacksonException`（unchecked） |
| `call().content()` 后再 `call().chatResponse()` 取元数据 | 二者是独立终端操作，各触发一次完整调用（第二个抛 "No CallAdvisors available"）；只调 `chatResponse()`，answer/metadata 都从它取 |
| `.stream()` 挂 `MessageChatMemoryAdvisor` + `.advisors(a -> a.param(SESSION_KEY, ...))` | 2.0.0 必抛 "conversationId cannot be null"：聚合后的响应 chunk 不携带 advisor params（call 路径正常）；流式用无记忆 Advisor 的 client |

## 第 6 课的验证技巧

`src/main/resources/docs/spring-ai-knowledge.md` 里的「Spring AI 创始人」信息是**故意编造的**，不在任何模型的预训练数据里。如果 `/lesson6/ask?q=Spring AI 的创始人是谁` 能答对，就证明检索真的生效了。改知识库时别把这条"修正"成真实信息，否则就失去了这个探针的意义。
