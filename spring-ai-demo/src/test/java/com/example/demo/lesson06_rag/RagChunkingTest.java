package com.example.demo.lesson06_rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * 第 6 课单元测试：验证知识库文档的切块逻辑。
 * 只读本地 classpath 文件并按段切分，不调用嵌入模型，无需 API Key。
 */
class RagChunkingTest {

    private final RagConfig ragConfig = new RagConfig();

    @Test
    void splitsKnowledgeDocIntoMultipleChunks() throws IOException {
        List<Document> docs = ragConfig.loadKnowledgeDocuments();

        // 至少被切成几段，说明切分真的生效了
        assertThat(docs).isNotEmpty();
        assertThat(docs.size()).isGreaterThan(3);

        // 每段都要有实际内容
        assertThat(docs).allSatisfy(d -> assertThat(d.getText()).isNotBlank());
    }
}