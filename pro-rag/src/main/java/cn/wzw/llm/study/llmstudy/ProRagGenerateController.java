package cn.wzw.llm.study.llmstudy;

import cn.wzw.llm.study.llmstudy.dto.generate.GeneratedFileResult;
import cn.wzw.llm.study.llmstudy.dto.ingestion.UploadedDocumentResult;
import cn.wzw.llm.study.llmstudy.dto.retrieval.GenerationReferenceBundle;
import cn.wzw.llm.study.llmstudy.dto.retrieval.ReferenceMaterial;
import cn.wzw.llm.study.llmstudy.output.DocumentOutputFormat;
import cn.wzw.llm.study.llmstudy.output.DocumentTemplateContext;
import cn.wzw.llm.study.llmstudy.output.ProRagTemplateOutputService;
import cn.wzw.llm.study.llmstudy.output.RenderedTemplateOutput;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档生成接口（含对话记忆）
 * 基于新通知文件 + 历史材料生成全新文档，支持多轮迭代修改
 */
@RestController
@RequestMapping("/pro-rag")
public class ProRagGenerateController {

    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter DISPLAY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ProRagConfiguration proRagConfiguration;

    @Autowired
    private ProRagRetrievalService proRagRetrievalService;

    @Autowired
    private ProRagDocumentIngestionService proRagDocumentIngestionService;

    @Autowired
    private ProRagTemplateOutputService proRagTemplateOutputService;

    @Autowired
    private DomainPromptConfig domainPromptConfig;

    /**
     * 生成/修改文档（流式输出）
     */
    @PostMapping("/generate")
    public Flux<String> generate(
            @RequestParam("chatId") String chatId,
            @RequestParam("instruction") String instruction,
            @RequestParam(value = "directiveFilename", required = false) String directiveFilename,
            @RequestParam(value = "domain", defaultValue = "bank_risk") String domain
    ) throws Exception {
        GenerationReferenceBundle referenceBundle = proRagRetrievalService.retrieveReferenceBundle(instruction, directiveFilename);
        String userMessage = buildUserMessage(instruction, referenceBundle, domain);
        ChatClient generateChatClient = proRagConfiguration.getGenerateChatClient();
        return generateChatClient.prompt()
                .user(userMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }

    /**
     * 同步生成并保存为文件
     */
    @PostMapping("/generate-file")
    public GeneratedFileResult generateFile(
            @RequestParam("chatId") String chatId,
            @RequestParam("instruction") String instruction,
            @RequestParam(value = "directiveFilename", required = false) String directiveFilename,
            @RequestParam(value = "outputFilename", required = false) String outputFilename,
            @RequestParam(value = "outputFormat", defaultValue = "markdown") String outputFormat,
            @RequestParam(value = "templateName", required = false) String templateName,
            @RequestParam(value = "documentTitle", required = false) String documentTitle,
            @RequestParam(value = "domain", defaultValue = "bank_risk") String domain
    ) throws Exception {
        return createGeneratedFile(
                chatId, instruction, directiveFilename,
                outputFilename, outputFormat, templateName, documentTitle,
                null, domain
        );
    }

    /**
     * 上传新通知文件后立即生成新材料
     */
    @PostMapping(value = "/upload-and-generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GeneratedFileResult uploadAndGenerate(
            @RequestParam("file") MultipartFile file,
            @RequestParam("chatId") String chatId,
            @RequestParam("instruction") String instruction,
            @RequestParam(value = "outputFilename", required = false) String outputFilename,
            @RequestParam(value = "outputFormat", defaultValue = "markdown") String outputFormat,
            @RequestParam(value = "templateName", required = false) String templateName,
            @RequestParam(value = "documentTitle", required = false) String documentTitle,
            @RequestParam(value = "domain", defaultValue = "bank_risk") String domain
    ) throws Exception {
        UploadedDocumentResult uploadResult = proRagDocumentIngestionService.upload(file);
        return createGeneratedFile(
                chatId, instruction, uploadResult.storedFilename(),
                outputFilename, outputFormat, templateName, documentTitle,
                uploadResult, domain
        );
    }

    private GeneratedFileResult createGeneratedFile(
            String chatId,
            String instruction,
            String directiveFilename,
            String outputFilename,
            String outputFormat,
            String templateName,
            String documentTitle,
            UploadedDocumentResult directiveFile,
            String domain
    ) throws Exception {
        GenerationReferenceBundle referenceBundle = proRagRetrievalService.retrieveReferenceBundle(instruction, directiveFilename);
        String generatedBody = generateContent(chatId, instruction, referenceBundle, domain);
        DocumentOutputFormat documentOutputFormat = DocumentOutputFormat.from(outputFormat);
        DocumentTemplateContext templateContext = buildTemplateContext(
                resolveDocumentTitle(documentTitle, directiveFilename, domain),
                instruction,
                directiveFilename,
                generatedBody,
                referenceBundle.referenceMaterials()
        );
        RenderedTemplateOutput rendered = proRagTemplateOutputService.renderAndSave(
                templateContext,
                documentOutputFormat,
                templateName,
                outputFilename
        );

        return new GeneratedFileResult(
                "success",
                rendered.templateName(),
                rendered.outputFormat(),
                directiveFilename,
                rendered.outputFilename(),
                rendered.outputFilePath(),
                rendered.previewContent(),
                referenceBundle.referenceMaterials(),
                directiveFile
        );
    }

    private String generateContent(String chatId, String instruction, GenerationReferenceBundle referenceBundle, String domain) throws Exception {
        ChatClient generateChatClient = proRagConfiguration.getGenerateChatClient();
        return generateChatClient.prompt()
                .user(buildUserMessage(instruction, referenceBundle, domain))
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .content();
    }

    private String buildUserMessage(String instruction, GenerationReferenceBundle referenceBundle, String domain) {
        if (!StringUtils.hasText(instruction)) {
            throw new IllegalArgumentException("instruction 不能为空");
        }

        List<String> mergedContents = referenceBundle.contents();
        String documentContent = mergedContents.isEmpty()
                ? "未检索到匹配材料，请根据已有要求输出基础框架，并把缺失信息标记为待补充。"
                : String.join("\n\n=========文档分隔线===========\n\n", mergedContents);
        String generatePrompt = domainPromptConfig.getDomain(domain).generatePrompt();
        return String.format(generatePrompt, documentContent, instruction.trim());
    }

    private DocumentTemplateContext buildTemplateContext(
            String documentTitle,
            String instruction,
            String directiveFilename,
            String generatedBody,
            List<ReferenceMaterial> referenceMaterials
    ) {
        return new DocumentTemplateContext(
                documentTitle,
                LocalDateTime.now().format(DISPLAY_TIME_FORMATTER),
                StringUtils.hasText(directiveFilename) ? directiveFilename : "无",
                instruction.trim(),
                buildReferenceFilesText(referenceMaterials),
                generatedBody,
                referenceMaterials
        );
    }

    private String buildReferenceFilesText(List<ReferenceMaterial> referenceMaterials) {
        if (referenceMaterials == null || referenceMaterials.isEmpty()) {
            return "未检索到明确参考材料。";
        }
        return referenceMaterials.stream()
                .map(item -> "- " + item.filename() + " | " + item.filePath())
                .collect(Collectors.joining("\n"));
    }

    private String resolveDocumentTitle(String documentTitle, String directiveFilename, String domain) {
        if (StringUtils.hasText(documentTitle)) {
            return documentTitle.trim();
        }
        if (StringUtils.hasText(directiveFilename)) {
            return directiveFilename.replaceAll("\\.[^.]+$", "") + "_生成稿";
        }
        String defaultName = domainPromptConfig.getDomain(domain).defaultFileName();
        return defaultName + "_" + LocalDateTime.now().format(FILE_TIME_FORMATTER);
    }
}
