package cn.wzw.llm.study.llmstudy.service;

import cn.wzw.llm.study.llmstudy.config.ParsingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 视觉模型服务：使用智谱 GLM-4V-Flash 识别图片内容。
 * 通过 Semaphore 控制 VL API 并发调用数，防止批量处理时打爆 API 限流。
 */
@Service
@Slf4j
public class VisionModelService {

    @Value("${spring.ai.zhipuai.api-key}")
    private String apiKey;

    @Value("${pro-rag.models.vision}")
    private String visionModel;

    private final ParsingProperties parsingProperties;

    private ChatModel visionChatModel;
    private Semaphore semaphore;

    public VisionModelService(ParsingProperties parsingProperties) {
        this.parsingProperties = parsingProperties;
    }

    @PostConstruct
    public void init() {
        ZhiPuAiApi zhiPuAiApi = ZhiPuAiApi.builder()
                .apiKey(new SimpleApiKey(apiKey))
                .restClientBuilder(RestClient.builder())
                .webClientBuilder(WebClient.builder())
                .build();

        ZhiPuAiChatOptions visionOptions = ZhiPuAiChatOptions.builder()
                .model(visionModel)
                .build();

        this.visionChatModel = new ZhiPuAiChatModel(zhiPuAiApi, visionOptions,
                ToolCallingManager.builder().build(),
                new RetryTemplate(),
                ObservationRegistry.NOOP,
                new DefaultToolExecutionEligibilityPredicate());

        int maxConcurrency = Math.max(1, parsingProperties.getVisionMaxConcurrency());
        this.semaphore = new Semaphore(maxConcurrency);
        log.info("VL 模型并发上限: {}", maxConcurrency);
    }

    /**
     * 识别图片内容，返回文字描述。
     * 受 Semaphore 限流控制，超时 120 秒。
     *
     * @param imageBytes 图片字节数据
     * @param mimeType   图片 MIME 类型
     * @param prompt     提示词（如 null 则使用默认提示）
     * @return 图片的文字描述
     */
    public String describeImage(byte[] imageBytes, MimeType mimeType, String prompt) {
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(120, TimeUnit.SECONDS);
            if (!acquired) {
                throw new RuntimeException("VL 模型并发已满，等待 120s 仍无法获取许可");
            }
            return doDescribeImage(imageBytes, mimeType, prompt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("VL 调用被中断", e);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private String doDescribeImage(byte[] imageBytes, MimeType mimeType, String prompt) {
        String userPrompt = (prompt != null) ? prompt : "请仔细识别并提取这张图片中的所有文字内容，保持原始格式和结构。如果图片中有表格，请用文本形式还原。";

        Media media = Media.builder()
                .mimeType(mimeType)
                .data(imageBytes)
                .build();

        UserMessage userMessage = UserMessage.builder()
                .text(userPrompt)
                .media(media)
                .build();

        Prompt visionPrompt = new Prompt(List.of(userMessage));
        ChatResponse response = visionChatModel.call(visionPrompt);
        String result = response.getResult().getOutput().getText();
        log.debug("视觉模型识别完成，结果长度: {} 字符", result != null ? result.length() : 0);
        return result;
    }
}
