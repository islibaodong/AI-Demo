package com.example.demo.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * .env 加载测试：不启动 Spring 上下文、不联网，直接构造 Environment 验证。
 * 既覆盖解析规则，也覆盖「真实环境变量 > .env > application.yml 默认值」的优先级链。
 */
class DotEnvEnvironmentPostProcessorTest {

    // ---------- 解析规则 ----------

    @Test
    void parsesPlainKeyValue() {
        Map<String, String> vars = DotEnvEnvironmentPostProcessor.parse(List.of("OPENAI_API_KEY=sk-123"));

        assertThat(vars).containsEntry("OPENAI_API_KEY", "sk-123");
    }

    @Test
    void skipsCommentsBlankLinesAndInvalidLines() {
        Map<String, String> vars = DotEnvEnvironmentPostProcessor.parse(List.of(
                "# 整行注释",
                "",                                    // 空行
                "   ",                                 // 纯空白
                "export OPENAI_BASE_URL=https://relay.example.com",   // export 前缀
                "QUOTED=\"带引号 的值\"",               // 双引号包裹，内部空格保留
                "SINGLE='单引号值'",                    // 单引号包裹
                "EMPTY=",                              // 空值 → 跳过，避免遮蔽默认值
                "没有等号的行",                          // 非法行 → 跳过
                "=没有键"                               // 键为空 → 跳过
        ));

        assertThat(vars).containsOnlyKeys(
                "OPENAI_BASE_URL", "QUOTED", "SINGLE");
        assertThat(vars).containsEntry("OPENAI_BASE_URL", "https://relay.example.com");
        assertThat(vars).containsEntry("QUOTED", "带引号 的值");
        assertThat(vars).containsEntry("SINGLE", "单引号值");
    }

    // ---------- 加载与优先级 ----------

    /**
     * 用临时 .env 驱动 processor，模拟三层配置源并存时的取值结果。
     * （TempDir 由 JUnit 负责清理，不会污染仓库。）
     */
    @Test
    void realEnvironmentWinsOverDotEnvWhichWinsOverYml(@TempDir Path dir) throws IOException {
        Path dotEnv = dir.resolve(".env");
        Files.writeString(dotEnv, """
                OPENAI_CHAT_MODEL=gpt-from-dotenv
                OPENAI_API_KEY=sk-from-dotenv
                """);

        StandardEnvironment env = new StandardEnvironment();
        // 模拟 application.yml 的占位默认值（Boot 里它的优先级低于环境变量，排在最后）
        env.getPropertySources()
                .addLast(new MapPropertySource("configData", Map.of("OPENAI_CHAT_MODEL", "gpt-from-yml")));
        // 模拟真实环境变量：替换掉同名的 systemEnvironment 源，避免受本机环境影响
        env.getPropertySources().replace("systemEnvironment",
                new MapPropertySource("systemEnvironment", Map.of("OPENAI_CHAT_MODEL", "gpt-from-real-env")));

        // SpringApplication 参数在本实现里用不到，传 null 即可
        processorFor(dotEnv).postProcessEnvironment(env, null);

        // .env 里独有的键要拿得到（application.yml 的 ${OPENAI_API_KEY:} 占位符靠它取值）
        assertThat(env.getProperty("OPENAI_API_KEY")).isEqualTo("sk-from-dotenv");
        // 真实环境变量 > .env
        assertThat(env.getProperty("OPENAI_CHAT_MODEL")).isEqualTo("gpt-from-real-env");
        // 属性源确实以固定名称注册，应用里可据此判断 .env 是否生效
        assertThat(env.getPropertySources().contains(DotEnvEnvironmentPostProcessor.SOURCE_NAME)).isTrue();
    }

    /** 没有 .env 时不能报错，也不能留下空的属性源。 */
    @Test
    void missingDotEnvIsNotAnError(@TempDir Path dir) {
        StandardEnvironment env = new StandardEnvironment();

        // 用一个不存在的路径调用（正常路径是工作目录下的 .env，这里指向空目录）
        processorFor(dir.resolve(".env")).postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains(DotEnvEnvironmentPostProcessor.SOURCE_NAME)).isFalse();
    }

    /** 正常路径是工作目录下的 .env，测试里重定向到临时目录，避免读写真实文件。 */
    private DotEnvEnvironmentPostProcessor processorFor(Path dotEnv) {
        return new DotEnvEnvironmentPostProcessor(new DeferredLogs()) {
            @Override
            Path dotEnvPath() {
                return dotEnv;
            }
        };
    }
}
