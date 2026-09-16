package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring AI 学习示例应用入口。
 *
 * <p>这是一份「循序渐进」的 LLM 应用教学代码：不引入第三方封装，直接用 Spring AI 官方 API，
 * 每个 lesson 是一个独立 package，聚焦一个核心概念，并标注与 Python 的 LangChain 的对应关系。</p>
 *
 * <p>运行方式见工程根目录的 README.md。</p>
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}