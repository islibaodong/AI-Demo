package com.example.demo.lesson07_image;

import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 7 课：图像生成（多模态）。
 *
 * <p>除了文本，Spring AI 也封装了图像模型。OpenAI 的 starter 会自动装配一个 {@link ImageModel} Bean
 * （对应 application.yml 里的 gpt-image-1）。</p>
 *
 * <p>用法极简：构造 <code>new ImagePrompt(描述词)</code> 丢给 <code>imageModel.call()</code>，
 * 从 <code>ImageResponse</code> 里取回生成的图片地址（URL）。更多尺寸、质量等参数可用
 * <code>ImageOptionsBuilder</code> 指定，本课先用最简形态。</p>
 *
 * <p><b>与 LangChain 对照</b>：ImageModel ≈ LangChain 的 <code>OpenAI ImageGeneration</code> 模型
 * （<code>image_gen.from_prompt(...)</code>）。</p>
 *
 * <p>试试：<code>curl "localhost:8080/lesson7?prompt=一只戴帽子的橘猫，水彩画风"</code>，
 * 返回的 JSON 里含可访问的图片 URL。</p>
 */
@RestController
public class Lesson07Controller {

    private final ImageModel imageModel;

    public Lesson07Controller(ImageModel imageModel) {
        this.imageModel = imageModel;
    }

    @GetMapping("/lesson7")
    public String generate(@RequestParam(defaultValue = "一只戴帽子的橘猫，水彩画风") String prompt) {
        ImageResponse response = imageModel.call(new ImagePrompt(prompt));

        // 取第一张生成图；若返回的是 base64 数据而非 URL，改用 getOutput().getB64Json()
        return response.getResult().getOutput().getUrl();
    }
}