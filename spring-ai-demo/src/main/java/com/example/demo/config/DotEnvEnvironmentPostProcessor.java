package com.example.demo.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

/**
 * 在 Spring 启动的最早期（配置文件加载前后）读取工作目录下的 <b>.env</b> 文件，
 * 把里面的 KEY=VALUE 注入为一个属性源——这样「从环境变量读配置」就不用手动 export 了。
 *
 * <p>为什么用 {@link EnvironmentPostProcessor} 而不是在业务代码里自己读？因为
 * <code>application.yml</code> 里的 <code>${OPENAI_API_KEY:}</code> 占位符是在
 * <b>绑定阶段</b>才解析的，只要在绑定前把 .env 变成属性源，占位符就能自然取到值，
 * 业务代码零改动。</p>
 *
 * <p><b>优先级</b>（高 → 低）：真实环境变量 &gt; <b>.env</b> &gt; application.yml 的默认值。
 * 实现方式是把 .env 属性源插到 <code>systemEnvironment</code> 的<b>后面</b>——
 * Spring 查找属性时先到先得，所以真实环境变量总能赢，.env 只补缺。
 * 这也符合 dotenv 的通行约定，方便 CI/生产环境用真实变量覆盖本地 .env。</p>
 *
 * <p><b>注册方式</b>：META-INF/spring.factories。注意 Boot 4 起接口搬到了
 * <code>org.springframework.boot</code> 包（旧 <code>org.springframework.boot.env</code>
 * 已 @Deprecated since 4.0.0），用错包名会被静默忽略——这是 Boot 3 迁 4 的一个暗坑。</p>
 *
 * <p><b>日志</b>：本类在日志系统初始化之前运行，直接用 LoggerFactory 打的日志会丢，
 * 所以通过构造器注入 {@link DeferredLogFactory}，先把日志暂存、日志系统就绪后回放。</p>
 */
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** .env 属性源的名称，可用它判断 .env 是否真的被加载了 */
    public static final String SOURCE_NAME = "dotEnv";

    private final Log log;

    public DotEnvEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(getClass());
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        load(environment, dotEnvPath());
    }

    /** .env 的位置：进程工作目录。mvn spring-boot:run 与 IDEA 默认都在模块目录（spring-ai-demo/）下运行，正合适。 */
    Path dotEnvPath() {
        return Path.of(".env");
    }

    private void load(ConfigurableEnvironment environment, Path path) {
        if (!Files.isRegularFile(path)) {
            log.info("工作目录下没有 .env（" + path.toAbsolutePath() + "），跳过；配置将来自真实环境变量与 application.yml 默认值");
            return;
        }
        try {
            Map<String, String> vars = parse(Files.readAllLines(path, StandardCharsets.UTF_8));
            if (vars.isEmpty()) {
                log.info(".env 没有可用的键值对，跳过");
                return;
            }
            PropertySource<?> source = new MapPropertySource(SOURCE_NAME, new HashMap<String, Object>(vars));
            var sources = environment.getPropertySources();
            if (sources.contains("systemEnvironment")) {
                sources.addAfter("systemEnvironment", source);   // 真实环境变量优先于 .env
            }
            else {
                sources.addLast(source);                          // 兜底：理论上 Boot 一定已注册 systemEnvironment
            }
            log.info("已从 " + path.toAbsolutePath() + " 加载 " + vars.size() + " 个变量"
                    + "（优先级：真实环境变量 > .env > application.yml 默认值）");
        }
        catch (IOException ex) {
            throw new IllegalStateException("无法读取 " + path.toAbsolutePath(), ex);
        }
    }

    /**
     * 逐行解析 .env 内容。支持：# 注释、空行、<code>export KEY=VALUE</code>、
     * 成对的单/双引号包裹的值。不支持行内 # 注释（值里可能合法包含 #）。
     * 键为空或值为空的行直接跳过——空值会以「空字符串」遮蔽 application.yml 里的默认值，属于坑。
     */
    static Map<String, String> parse(List<String> lines) {
        Map<String, String> vars = new HashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
                continue;
            }
            value = stripMatchingQuotes(value);
            vars.put(key, value);
        }
        return vars;
    }

    private static String stripMatchingQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
