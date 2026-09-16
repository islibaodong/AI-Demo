# AI-Demo

AI 学习实验仓库。当前包含一个完整的 **Spring AI（Java）教学工程**，后续的 AI 框架学习示例也会放在这里。

## 仓库内容

| 目录 | 说明 |
|------|------|
| [`spring-ai-demo/`](spring-ai-demo/) | **Spring AI 学习实验室**：7 节课由浅入深（最简调用 → 提示词/结构化输出 → 流式 → 会话记忆 → 函数调用 → RAG → 图像生成），每课一个 `@RestController`，可独立 curl 体验，中文注释并标注与 Python LangChain 的概念对照。**详细文档见 [`spring-ai-demo/README.md`](spring-ai-demo/README.md)**。 |
| [`docs/`](docs/) | 学习计划与笔记（如 `202609.md`）。 |
| [`CLAUDE.md`](CLAUDE.md) | 给 Claude Code 的工程说明（本机构建注意事项、版本矩阵、架构约定）。 |

## 快速开始

```bash
cd spring-ai-demo

# 本机默认 JDK 是 8，必须先切到 Java 21
export JAVA_HOME=/Users/islibaodong/Library/Java/JavaVirtualMachines/ms-21.0.9/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

# 离线构建 + 跑全部测试（5 个用例，不需要 API Key）
mvn clean package

# 启动应用（需要 API Key）
export OPENAI_API_KEY=sk-你的key
mvn spring-boot:run
# 浏览器打开 http://localhost:8080/ 有 demo 首页
```

没有 API Key 也能 `mvn test` 全绿 —— 所有测试均为离线测试。

## 版本组合

Spring AI **2.0.0** + Spring Boot **4.1.0** + Java **21**（三者强耦合，升级前先看 [`CLAUDE.md`](CLAUDE.md) 的版本矩阵）。

## License

[Apache License 2.0](LICENSE)
