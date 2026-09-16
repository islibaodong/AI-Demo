package com.example.demo.lesson02_prompt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * 第 2 课单元测试：验证 BeanOutputConverter 能把模型的 JSON 输出解析成 Java 对象。
 * 纯本地解析，不联网，无需 API Key。
 */
class BeanOutputConverterTest {

    private final BeanOutputConverter<TranslationResult> converter =
            new BeanOutputConverter<>(TranslationResult.class);

    @Test
    void parsesJsonIntoPojo() {
        String modelJson = """
                {"en":"Hello","zh":"你好","note":"正式场合的通用问候。"}
                """;

        TranslationResult result = converter.convert(modelJson);

        assertThat(result).isNotNull();
        assertThat(result.getEn()).isEqualTo("Hello");
        assertThat(result.getZh()).isEqualTo("你好");
        assertThat(result.getNote()).contains("问候");
    }
}