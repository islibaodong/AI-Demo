package com.example.demo.lesson02_prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.PromptTemplate;

/**
 * 第 2 课单元测试：验证 PromptTemplate 的占位符替换正确。
 * 只测模板渲染，不调用远程模型，因此无需 API Key 也能跑。
 */
class PromptTemplateTest {

    @Test
    void rendersPlaceholders() {
        PromptTemplate template = new PromptTemplate("请用{tone}的语气，把“{text}”翻译成中文。只输出译文。");

        String rendered = template.render(Map.of("tone", "轻松友好", "text", "hello"));

        assertThat(rendered)
                .contains("轻松友好")
                .contains("hello")
                .doesNotContain("{tone}")
                .doesNotContain("{text}");
    }
}