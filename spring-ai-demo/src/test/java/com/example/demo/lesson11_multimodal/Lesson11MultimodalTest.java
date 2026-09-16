package com.example.demo.lesson11_multimodal;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 11 课单元测试：不调用任何模型。验证多模态消息的组装方式，
 * 以及内置 demo 图片是一张合法的 PNG（视觉探针的前提）。
 */
class Lesson11MultimodalTest {

    @Test
    void demoImageIsAValidPng() throws Exception {
        byte[] bytes;
        try (InputStream in = new ClassPathResource("images/demo-scene.png").getInputStream()) {
            bytes = in.readAllBytes();
        }
        assertThat(bytes.length).isGreaterThan(100);
        assertThat(bytes[0]).isEqualTo((byte) 0x89);   // PNG 魔数 \x89PNG\r\n\x1a\n
        assertThat(new String(bytes, 1, 3)).isEqualTo("PNG");
    }

    @Test
    void userMessageCarriesLocalImageMedia() throws Exception {
        Media image = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(new ClassPathResource("images/demo-scene.png"))
                .build();
        UserMessage message = UserMessage.builder().text("图里有什么？").media(image).build();

        assertThat(message.getText()).isEqualTo("图里有什么？");
        assertThat(message.getMedia()).hasSize(1);
        Media carried = message.getMedia().get(0);
        assertThat(carried.getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_PNG);
        // Media 挂的 Resource 能真正读出内容（发给模型时就是这些字节）
        assertThat(((byte[]) carried.getData()).length).isGreaterThan(100);
    }

    @Test
    void userMessageCarriesRemoteUrlMedia() {
        Media remote = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_JPEG)
                .data(java.net.URI.create("https://example.com/cat.jpg"))
                .build();
        UserMessage message = UserMessage.builder().text("这是什么？").media(List.of(remote)).build();

        assertThat(message.getMedia()).hasSize(1);
        // Media 内部会把 URI 归一化为字符串存储
        assertThat(message.getMedia().get(0).getData()).isEqualTo("https://example.com/cat.jpg");
    }
}
