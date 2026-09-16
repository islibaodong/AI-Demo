# Spring AI 学习实验室（Java）

一份**由浅入深、可运行**的 Spring AI 教学工程：7 节课，每课一个核心概念，每个接口都能直接 curl 体验，
源码里配有中文注释，并标注了与 Python **LangChain** 的对应概念。

> ⚠️ 先纠正一个常见混淆：**LangChain 是 Python 生态的框架**，Java 里没有官方 LangChain。
> Java 生态的等价物就是 **Spring AI**。本工程教的就是 Spring AI，同时帮你把已有的 LangChain 概念迁移过来。

---

## 一、环境准备

| 依赖 | 要求 | 说明 |
|------|------|------|
| JDK | **17+（推荐 21）** | Spring AI 2.0 需要 Java 17+。**你机器上默认的 `java` 是 8，必须切换**，否则编译报错。 |
| Maven | 3.8+ | 已安装 3.9.11 即可。 |
| OpenAI API Key | 必需 | 用于真正调用模型。 |

### 1) 切换到 Java 21

你本机已装 Java 21（`~/Library/Java/JavaVirtualMachines/ms-21.0.9`）。每次开新终端都要设置：

```bash
export JAVA_HOME=/Users/islibaodong/Library/Java/JavaVirtualMachines/ms-21.0.9/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
java -version   # 应显示 21.x
```

> 想一劳永逸，可把上面两行加进 `~/.zshrc`。

### 2) 设置 API Key

```bash
export OPENAI_API_KEY=sk-你的key
```

工程从环境变量读取它（见 `src/main/resources/application.yml` 里的
`spring.ai.openai.api-key=${OPENAI_API_KEY:}`），**不要把 key 写进代码或提交到仓库**。

---

## 二、运行

```bash
cd spring-ai-demo
export JAVA_HOME=/Users/islibaodong/Library/Java/JavaVirtualMachines/ms-21.0.9/Contents/Home
export OPENAI_API_KEY=sk-你的key
mvn spring-boot:run
```

启动后浏览器打开 **<http://localhost:8080/>**，有一个列出全部 7 个 demo 入口的首页。

### 没有 Key 也能做的两件事
- `mvn test` —— 5 个单元测试全部离线运行（模板渲染、JSON 解析、记忆窗口裁剪、RAG 切块、配置项绑定），**不需要 Key**。
- 启动应用后打开首页 —— 页面能正常显示；但一旦真的调用模型（如 `/lesson1`），会返回 500。

---

## 三、7 节课速查

| # | 主题 | 端点 | 核心 API |
|---|------|------|----------|
| 1 | 最简调用 | `GET /lesson1?q=...` | `ChatClient` · `prompt().user().call().content()` |
| 2 | 提示词 & 结构化输出 | `GET /lesson2/translate?text=`<br>`GET /lesson2/structured?text=` | `PromptTemplate` · few-shot · `.entity(Class)` |
| 3 | 流式输出 | `GET /lesson3/stream?topic=` | `.stream().content()` → `Flux<String>`（SSE） |
| 4 | 会话记忆 | `POST /lesson4/chat/{sessionId}` | `MessageWindowChatMemory` · `MessageChatMemoryAdvisor` |
| 5 | 函数调用 | `GET /lesson5?q=...` | `@Tool` · `@ToolParam` · `.defaultTools(obj)` |
| 6 | RAG 检索增强 | `GET /lesson6/ingest`<br>`GET /lesson6/ask?q=` | `EmbeddingModel` · `SimpleVectorStore` · `similaritySearch` |
| 7 | 图像生成 | `GET /lesson7?prompt=` | `ImageModel` · `ImagePrompt` |

### 逐个 curl 体验

```bash
# 1 最简调用
curl "localhost:8080/lesson1?q=用一句话介绍你自己"

# 2 结构化输出（返回 JSON）
curl "localhost:8080/lesson2/structured?text=Spring%20AI%20rocks"

# 3 流式（-N 关闭缓冲，观察逐字输出）
curl -N "localhost:8080/lesson3/stream?topic=Reactive"

# 4 记忆：同一 sessionId 多轮，第二次能记住名字
curl -X POST "localhost:8080/lesson4/chat/abc" -d "我的名字叫小明"
curl -X POST "localhost:8080/lesson4/chat/abc" -d "我叫什么名字？"

# 5 函数调用：模型会自己决定调用天气/计算器/时间工具
curl "localhost:8080/lesson5?q=北京今天天气如何"
curl "localhost:8080/lesson5?q=帮我算一下 (12+8)*4 等于几"

# 6 RAG：先灌库，再基于本地资料问答
curl "localhost:8080/lesson6/ingest"
curl "localhost:8080/lesson6/ask?q=Spring%20AI%20的创始人是谁"

# 7 图像生成
curl "localhost:8080/lesson7?prompt=一只戴帽子的橘猫，水彩画风"
```

> **第 6 课的验证技巧**：`docs/spring-ai-knowledge.md` 里的「创始人」信息是编造的、不在模型预训练数据里。
> 如果问答能答对，就说明检索真的生效了 —— 这是判断 RAG 是否工作的最直接方法。

---

## 四、LangChain ↔ Spring AI 概念对照

| LangChain (Python) | Spring AI (Java) | 本工程位置 |
|--------------------|------------------|-----------|
| `ChatOpenAI` | `ChatClient` / `OpenAiChatModel` | `lesson01_basic` |
| `PromptTemplate` | `PromptTemplate` | `lesson02_prompt` |
| `with_structured_output` / OutputParser | `.entity(Class)` + `BeanOutputConverter` | `lesson02_prompt` |
| `streaming=True` / `runnable.stream()` | `.stream().content()` | `lesson03_stream` |
| `RunnableWithMessageHistory` / `ChatMessageHistory` | `ChatMemory` + `MessageChatMemoryAdvisor` | `lesson04_memory` |
| `WindowBufferWindowMemory` | `MessageWindowChatMemory` | `lesson04_memory` |
| `@tool` / `bind_tools()` | `@Tool` / `.defaultTools()` | `lesson05_functions` |
| `OpenAIEmbeddings` | `EmbeddingModel` | `lesson06_rag` |
| `FAISS` / `Chroma` | `SimpleVectorStore`（或 PGVector 等） | `lesson06_rag` |
| `RecursiveCharacterTextSplitter` | 自行切块（本工程用按段切分演示） | `RagConfig.loadKnowledgeDocuments` |
| `RetrievalQA` / Retriever | 手写 Retrieve→Augment→Generate（或 `QuestionAnswerAdvisor`） | `lesson06_rag` |
| `OpenAI ImageGeneration` | `ImageModel` | `lesson07_image` |

---

## 五、工程结构

```
spring-ai-demo/
├── pom.xml                       # Spring Boot 4.1 + Spring AI 2.0 BOM，Java 21
├── README.md
└── src/
    ├── main/
    │   ├── java/com/example/demo/
    │   │   ├── DemoApplication.java
    │   │   ├── lesson01_basic/     # 最简调用
    │   │   ├── lesson02_prompt/    # 模板 / few-shot / 结构化输出
    │   │   ├── lesson03_stream/    # 流式 SSE
    │   │   ├── lesson04_memory/    # 会话记忆
    │   │   ├── lesson05_functions/ # 函数调用（@Tool）
    │   │   ├── lesson06_rag/       # 检索增强 RAG
    │   │   └── lesson07_image/     # 图像生成
    │   └── resources/
    │       ├── application.yml        # 所有配置集中在此
    │       ├── static/index.html             # demo 首页
    │       └── docs/spring-ai-knowledge.md   # RAG 演示知识库
    └── test/java/com/example/demo/           # 5 个离线单元测试
```

---

## 六、编码约定（避免中文乱码）

本工程所有文件统一为 **UTF-8 + LF**，并通过三处配置固定下来：

| 位置 | 作用 |
|------|------|
| `.editorconfig` | 全工程声明 `charset = utf-8`、`end_of_line = lf` |
| `../.idea/encodings.xml` | IntelliJ 工程级编码设为 UTF-8 |
| `pom.xml` 的 `project.build.sourceEncoding` | Maven 编译源码与资源时一律按 UTF-8 处理 |

### 为什么配置文件用 `application.yml` 而不是 `application.properties`

这不是风格偏好，而是**为了绕开 `.properties` 的编码历史包袱**：

- Java 的 `.properties` 规范**规定默认编码是 ISO-8859-1**（1996 年的遗留决定），
  IntelliJ 严格遵循这个规范，于是文件里 UTF-8 的中文注释会被显示成 `å¦ä¹ ` 这类乱码。
- 这个默认值在 IntelliJ 里是**全局设置**，新项目默认继承 ISO-8859-1；
  工程级的 `.editorconfig` / `.idea/encodings.xml` 只有在 IntelliJ **打开的是本工程**时才生效。
- YAML 规范则**强制要求 UTF-8**，IntelliJ 对 `.yml` 默认就按 UTF-8 处理，没有任何开关需要改。

**关键澄清：这自始至终只是一个"显示"问题，文件内容和运行时都是好的。**
已实测验证过：

| 层次 | 验证方式 | 结果 |
|------|----------|------|
| 文件字节 | 用 Python 逐字节解码全部 29 个源文件 | UTF-8 全部成功，latin-1 恰好复现乱码 → 文件本身是干净的 UTF-8 |
| 编译产物 | 检查 class 常量池与打包后的 jar | 中文完好 |
| HTTP 响应 | 起服务后抓包看真实字节 | `Content-Type: charset=UTF-8`，`中` = `e4 b8 ad`，正确 |

所以哪怕你在编辑器里看到乱码，程序跑起来也是对的。换成 `.yml` 只是为了让**编辑器里也不再刺眼**。

### 如果你在别的项目里也需要 `.properties` 显示正常

去 `Settings → Editor → File Encodings`，把 **Default encoding for properties files** 设为 **UTF-8**——
注意这个改动是**全局的**，会影响你所有项目，本工程没有替你动它。
若某个文件已被错误编码保存过，用 `File → Reload with Encoding → UTF-8` 重新加载。

---

## 七、常见问题

**Q：编译报 `invalid target release` 或找不到 Java 17 特性？**
默认 JDK 是 8。请先 `export JAVA_HOME=...ms-21.0.9/...`（见第一节）。

**Q：调用接口返回 500 / `401 Unauthorized`？**
没设 `OPENAI_API_KEY`，或 key 无效/额度不足。设置后重启应用。

**Q：`/lesson6/ask` 说「向量库为空」？**
先访问 `/lesson6/ingest` 灌入知识库。注意 ingest 也会调用嵌入模型，需要 Key。

**Q：想换模型或供应商？**
改 `application.yml` 的 `spring.ai.openai.chat.model`；换供应商（如 Azure、Gemini）
只需替换 starter 依赖，业务代码几乎不用改 —— 这正是 Spring AI 抽象层的价值。

---

## 八、下一步建议

学完这 7 课，可以继续深入：
1. **把 RAG 换成向量数据库**：PGVector / Milvus，让知识库持久化。
2. **用 `QuestionAnswerAdvisor`** 替代手写 RAG，并加上「引用来源」返回。
3. **Advisor 进阶**：自定义 Advisor 做敏感词过滤、日志审计、多轮改写。
4. **MCP（Model Context Protocol）**：把外部系统以标准协议接入模型。
5. **可观测性**：接入 Micrometer / OpenTelemetry 观察 token 消耗与延迟。
