package com.example.demo.lesson11_multimodal;

import java.net.URI;

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 11 课：多模态对话 —— 图片输入 + 语音合成/识别。
 *
 * <p>前面所有课的 user 消息都只是<b>文本</b>。gpt-4o 这类多模态模型能直接"看图说话"，
 * Spring AI 的表达方式是：UserMessage 除了 text 还可以挂一组 {@link Media}
 * （图片/音频/视频皆可，本地 Resource 或远程 URI 均可）：</p>
 *
 * <pre>{@code
 * UserMessage msg = UserMessage.builder()
 *         .text("图里有什么？")
 *         .media(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG)
 *                 .data(new ClassPathResource("images/demo-scene.png")).build())
 *         .build();
 * }</pre>
 *
 * <p>语音部分用的是 OpenAI 的另外两个端点（与 chat 无关，starter 自动装配成 Bean）：</p>
 * <ul>
 *   <li><b>TTS 文字转语音</b>：OpenAiAudioSpeechModel（/v1/audio/speech），
 *       一行 <code>call(text)</code> 返回 mp3 字节。</li>
 *   <li><b>STT 语音转文字</b>：TranscriptionModel（/v1/audio/transcriptions，即 Whisper），
 *       POST 音频字节进去，返回文字。</li>
 * </ul>
 *
 * <p><b>验证技巧（与第 6 课"创始人"探针同款思路）</b>：resources/images/demo-scene.png
 * 是程序手绘的图——蓝天、草地、右上角一个红色大圆。问"图里有什么"，模型若能说出
 * "红色的太阳/圆"，说明视觉输入真的生效了，而不是在凭空编。</p>
 *
 * <p><b>与 LangChain 对照</b>：多模态消息 ≈ LangChain 消息里的
 * <code>HumanMessage(content=[{"type":"text",...},{"type":"image_url",...}])</code>；
 * TTS/STT ≈ <code>OpenAIText2SpeechModel / OpenAIWhisperModel</code>。</p>
 *
 * <p>试试：</p>
 * <ul>
 *   <li><code>curl "localhost:8080/lesson11/vision?q=图里有什么？"</code></li>
 *   <li><code>curl "localhost:8080/lesson11/vision?url=https://...&amp;q=描述这张图"</code> —— 换成你自己的图片 URL</li>
 *   <li><code>curl -o out.mp3 "localhost:8080/lesson11/speak?text=你好，世界"</code> —— 听听生成的语音</li>
 *   <li><code>curl -X POST "localhost:8080/lesson11/transcribe" -H "Content-Type: audio/mpeg" --data-binary @out.mp3</code> —— 刚才的语音转回文字</li>
 * </ul>
 */
@RestController
public class Lesson11Controller {

    private final ChatClient chatClient;
    private final OpenAiAudioSpeechModel ttsModel;
    private final TranscriptionModel sttModel;

    public Lesson11Controller(ChatModel chatModel,
                              OpenAiAudioSpeechModel ttsModel,
                              TranscriptionModel sttModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.ttsModel = ttsModel;
        this.sttModel = sttModel;
    }

    /** 视觉问答：默认用工程内置的 demo 图片，可通过 url 参数换成任意外网图片 */
    @GetMapping("/lesson11/vision")
    public String vision(@RequestParam(defaultValue = "图里有什么？请简要描述。") String q,
                         @RequestParam(required = false) String url) {
        Media image = url != null
                ? Media.builder().mimeType(guessImageMime(url)).data(URI.create(url)).build()
                : Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG)
                        .data(new ClassPathResource("images/demo-scene.png")).build();

        Prompt prompt = new Prompt(UserMessage.builder().text(q).media(image).build());
        return chatClient.prompt(prompt).call().content();
    }

    /** TTS：文字 → mp3 字节，浏览器可直接播放（OpenAI 的 speech 端点，独立于 chat） */
    @GetMapping("/lesson11/speak")
    public ResponseEntity<byte[]> speak(@RequestParam String text) {
        byte[] audio = ttsModel.call(text);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"speech.mp3\"")
                .body(audio);
    }

    /** STT：上传音频字节（Content-Type 指明格式，如 audio/mpeg / audio/wav）→ 转成文字 */
    @PostMapping("/lesson11/transcribe")
    public String transcribe(@RequestBody byte[] audio,
                             @RequestHeader HttpHeaders headers) {
        MediaType contentType = headers.getContentType() == null
                ? MediaType.parseMediaType("audio/mpeg") : headers.getContentType();
        // OpenAI 按文件扩展名识别音频格式，所以用 ByteArrayResource 并补一个带扩展名的文件名
        ByteArrayResource resource = new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return "audio." + contentType.getSubtype();
            }
        };
        AudioTranscriptionPrompt prompt = new AudioTranscriptionPrompt(resource);
        AudioTranscriptionResponse response = sttModel.call(prompt);
        return response.getResults().get(0).getOutput();
    }

    private static MimeType guessImageMime(String url) {
        String lower = url.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MimeTypeUtils.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MimeTypeUtils.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MimeTypeUtils.parseMimeType("image/webp");
        return MimeTypeUtils.IMAGE_PNG;
    }
}
