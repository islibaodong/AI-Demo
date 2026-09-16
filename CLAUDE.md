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

# 构建 + 跑全部测试（5 个用例，全部离线，不需要 API Key）
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

`src/main/java/com/example/demo/lessonNN_xxx/`，每课一个 `@RestController`，聚焦一个概念，互相不依赖。课程顺序即学习顺序：最简调用 → 提示词/结构化输出 → 流式 → 记忆 → 函数调用 → RAG → 图像。

**贯穿全工程的模式**：各 Controller 都注入自动配置好的 `ChatClient.Builder`，再按本课需要定制后 `.build()` 出自己的 `ChatClient` 实例——

- lesson01/02/03/06：`builder.build()`，朴素用法
- lesson04：`builder.defaultAdvisors(MessageChatMemoryAdvisor...)`，挂记忆
- lesson05：`builder.defaultTools(appTools)`，挂工具

所以新增课程时，**不要去定义新的 `ChatClient` Bean**，而是照这个模式从共享 Builder 派生。

### Advisor 是核心扩展点

`Advisor` 是请求/响应拦截器，记忆（lesson04）、RAG（可用 `QuestionAnswerAdvisor` 替代手写）都靠它。lesson04 里有个必须知道的事实：会话 id 的 param key 是**字面量字符串** `"chat_memory_conversation_id"`，框架没有导出公开常量，写错会静默退化成所有会话共享同一份记忆。

### RAG 是两段式的，不是启动时自动灌库

`RagConfig` 定义 `VectorStore` Bean（`SimpleVectorStore` 内存实现）和 `loadKnowledgeDocuments()` 切块工具，但**灌库由 `GET /lesson6/ingest` 按需触发**，问答走 `GET /lesson6/ask`。

这是刻意的取舍：如果启动时就灌库，没有 API Key 的环境会直接启动失败，整个教学工程就跑不起来了。改这块时保留这个性质。

`Lesson06Controller` 里手写了 Retrieve → Augment → Generate 三步而不是用 `QuestionAnswerAdvisor`，目的是把原理摊开给人看。

## 约定

**配置文件用 `application.yml`，不要改回 `application.properties`。** 后者规范默认编码是 ISO-8859-1，IntelliJ 据此解析会把 UTF-8 中文注释显示成乱码，且该默认值是 IDE 全局设置、新项目一律继承，工程内改不动。YAML 规范强制 UTF-8。这不是风格偏好。

**API Key 只从环境变量 `OPENAI_API_KEY` 读**（`application.yml` 里写 `${OPENAI_API_KEY:}`），绝不写进代码或提交。

**测试必须全部离线。** 现有 5 个测试（`ConfigBindingTest`、`PromptTemplateTest`、`BeanOutputConverterTest`、`MemoryWindowTest`、`RagChunkingTest`）都不联网、不需要 Key，覆盖模板渲染、JSON 解析、记忆窗口裁剪、RAG 切块、以及 YAML 配置项绑定。新增测试请保持这个性质——没有 Key 的人也要能 `mvn test` 全绿。

**注释用中文，并标注对应的 LangChain 概念。** 这个工程的目标读者是从 Python LangChain 转过来的人（注意：LangChain 是 Python 生态的，Java 没有官方对应物，本工程教的就是 Spring AI 本身）。

## Spring AI 2.0 API 的实际签名

写代码前建议用 `javap` 对着本地 jar 确认，以下几条是凭印象容易写错的：

| 想当然的写法 | 2.0 实际 |
|---|---|
| `MessageWindowChatMemory.builder().windowSize(n)` | `.maxMessages(n)` |
| `Document.getContent()` | `Document.getText()` |
| `ChatMemoryAdvisor` | 仍叫 `MessageChatMemoryAdvisor` |
| `SearchRequest.builder()` 后直接 `.build()` | 需 `.query(q).topK(k).build()` |

## 第 6 课的验证技巧

`src/main/resources/docs/spring-ai-knowledge.md` 里的「Spring AI 创始人」信息是**故意编造的**，不在任何模型的预训练数据里。如果 `/lesson6/ask?q=Spring AI 的创始人是谁` 能答对，就证明检索真的生效了。改知识库时别把这条"修正"成真实信息，否则就失去了这个探针的意义。
