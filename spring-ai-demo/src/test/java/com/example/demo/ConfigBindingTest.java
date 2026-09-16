package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * 配置绑定测试：确认 application.yml 里的配置项真的绑定到了对应属性。
 *
 * <p>存在的意义：本项目原本用 application.properties，因 IntelliJ 按 ISO-8859-1
 * 解析 .properties 导致中文注释乱码，故迁移到 application.yml。
 * 这个测试守住迁移后配置不丢、不错位。</p>
 *
 * <p>不调用大模型，无需 API Key。</p>
 */
@SpringBootTest
class ConfigBindingTest {

    @Autowired
    private Environment env;

    @Test
    void yamlConfigBindsToExpectedKeys() {
        assertThat(env.getProperty("server.port")).isEqualTo("8080");

        assertThat(env.getProperty("spring.ai.openai.chat.model")).isEqualTo("gpt-4o");
        assertThat(env.getProperty("spring.ai.openai.chat.temperature")).isEqualTo("0.7");
        assertThat(env.getProperty("spring.ai.openai.embedding.model"))
                .isEqualTo("text-embedding-3-small");
        assertThat(env.getProperty("spring.ai.openai.image.model")).isEqualTo("gpt-image-1");

        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("openai");
        assertThat(env.getProperty("spring.ai.model.embedding")).isEqualTo("openai");
        assertThat(env.getProperty("spring.ai.model.image")).isEqualTo("openai");

        assertThat(env.getProperty("logging.level.org.springframework.ai")).isEqualTo("DEBUG");
    }
}