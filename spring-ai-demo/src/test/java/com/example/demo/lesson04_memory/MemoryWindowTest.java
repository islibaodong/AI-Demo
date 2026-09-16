package com.example.demo.lesson04_memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 第 4 课单元测试：验证「窗口式记忆」会丢弃超出窗口的最旧消息。
 * 纯内存逻辑，不联网，无需 API Key。
 */
class MemoryWindowTest {

    @Test
    void keepsOnlyMostRecentMessagesWithinWindow() {
        // 窗口最大 3 条
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(3)
                .build();

        String sid = "test-session";
        // 连续加入 5 条消息（用户/助手交替）
        memory.add(sid, new UserMessage("1"));
        memory.add(sid, new AssistantMessage("2"));
        memory.add(sid, new UserMessage("3"));
        memory.add(sid, new AssistantMessage("4"));
        memory.add(sid, new UserMessage("5"));

        var history = memory.get(sid);

        // 窗口裁剪后，最多只保留最近 3 条，且最旧的两条被丢弃
        assertThat(history).hasSizeLessThanOrEqualTo(3);
        assertThat(history.get(history.size() - 1).getText()).isEqualTo("5");
    }
}