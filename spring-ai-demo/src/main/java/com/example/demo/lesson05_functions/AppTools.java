package com.example.demo.lesson05_functions;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * 第 5 课：函数调用（Function Calling / Tools）—— 让 LLM 能调用你的真实代码。
 *
 * <p>LLM 本身只会「生成文字」，无法查数据库、调 API 或读系统时间。函数调用让模型：</p>
 * <ol>
 *   <li>看到一批可用的「工具」（就是想办法对象上的 <b>@Tool 方法</b>）。</li>
 *   <li>判断当前问题需不需要某个工具，如果需要就<b>自己决定参数</b>并请求调用。</li>
 *   <li>框架替你调用方法拿到结果，再把结果回喂给模型，让它据此组织最终回答。</li>
 * </ol>
 *
 * <p>写工具只要两步：给普通方法加 <b>@Tool</b>（可给描述），参数用 <b>@ToolParam</b> 写清楚含义。
 * 描述写得越清晰，模型越能正确选择、填充参数。</p>
 *
 * <p><b>与 LangChain 对照</b>：@Tool ≈ LangChain 的 <code>@tool 装饰器</code>；
 * 模型自行决定是否调用 ≈ <code>bind_tools()</code> + agent 的 tool loop。</p>
 */
@Service
public class AppTools {

    /**
     * 模拟查天气：真实场景这里应调用第三方天气 API。
     */
    @Tool(name = "getWeather", description = "根据城市名查询当地今天的天气情况")
    public String getWeather(@ToolParam(description = "城市名，例如：北京") String city) {
        // 演示用，直接返回编造的天气
        return city + "：今天 25℃ 晴，微风。";
    }

    /**
     * 模拟计算器：四则运算。
     */
    @Tool(name = "calculator", description = "执行两个整数的四则运算，运算符支持 + - * /")
    public Double calculator(
            @ToolParam(description = "第一个操作数") double a,
            @ToolParam(description = "运算符，取值为 + - * /") String op,
            @ToolParam(description = "第二个操作数") double b) {
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> b == 0 ? Double.NaN : a / b;
            default -> throw new IllegalArgumentException("不支持的运算符: " + op);
        };
    }

    /**
     * 获取当前时间。
     */
    @Tool(description = "获取服务器当前的日期和时间")
    public String currentTime() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(LocalDateTime.now());
    }
}