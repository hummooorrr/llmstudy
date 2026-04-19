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

    private static final String GENERATE_PROMPT =
            "你是一名专业的银行风控合规文书撰写专家。你的任务是根据用户指令和参考资料，撰写或修改一份正式的银行风控合规文档。\n\n"
                    + "## 撰写规则\n"
                    + "1. 严格基于提供的参考资料内容进行撰写，不得编造不存在的政策、法规或数据\n"
                    + "2. 文档语言应专业、严谨、规范，符合银行业监管文书的行文风格\n"
                    + "3. 结构清晰，层次分明，使用规范的标题编号（一、二、三... 1. 2. 3. ...）\n"
                    + "4. 如用户指令是对已有文档的修改意见，请在保留原文档框架基础上进行针对性调整\n"
                    + "5. 如参考资料不足以支撑完整撰写，在相关段落注明\"待补充\"并说明需要哪些额外信息\n\n"
                    + "## 参考资料\n"
                    + "%s\n\n"
                    + "## 用户指令\n"
                    + "%s\n\n"
                    + "请输出完整的文档内容：\n";

    /**
     * 生成/修改文档（流式输出）
     *
     * @param chatId            会话ID，同一ID保持对话记忆，支持多轮迭代修改
     * @param instruction       生成/修改指令
     * @param directiveFilename 可选，新下发的通知文件名，用于追加检索该文件内容
     */
    @PostMapping("/generate")
    public Flux<String> generate(
            @RequestParam("chatId") String chatId,
            @RequestParam("instruction") String instruction,
            @RequestParam(value = "directiveFilename", required = false) String directiveFilename
    ) throws Exception {
        GenerationReferenceBundle referenceBundle = proRagRetrievalService.retrieveReferenceBundle(instruction, directiveFilename);
        String userMessage = buildUserMessage(instruction, referenceBundle);
        ChatClient generateChatClient = proRagConfiguration.getGenerateChatClient();
        return generateChatClient.prompt()
                .user(userMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }

    /**
     * 同步生成并保存为文件，便于直接沉淀成可复用材料。
     */
    @PostMapping("/generate-file")
    public GeneratedFileResult generateFile(
            @RequestParam("chatId") String chatId,
            @RequestParam("instruction") String instruction,
            @RequestParam(value = "directiveFilename", required = false) String directiveFilename,
            @RequestParam(value = "outputFilename", required = false) String outputFilename,
            @RequestParam(value = "outputFormat", defaultValue = "markdown") String outputFormat,
            @RequestParam(value = "templateName", required = false) String templateName,
            @RequestParam(value = "documentTitle", required = false) String documentTitle
    ) throws Exception {
        return createGeneratedFile(
                chatId,
                instruction,
                directiveFilename,
                outputFilename,
                outputFormat,
                templateName,
                documentTitle,
                null
        );
    }

    /**
     * 上传新通知文件后立即生成新材料，减少前后端编排复杂度。
     */
    @PostMapping(value = "/upload-and-generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GeneratedFileResult uploadAndGenerate(
            @RequestParam("file") MultipartFile file,
            @RequestParam("chatId") String chatId,
            @RequestParam("instruction") String instruction,
            @RequestParam(value = "outputFilename", required = false) String outputFilename,
            @RequestParam(value = "outputFormat", defaultValue = "markdown") String outputFormat,
            @RequestParam(value = "templateName", required = false) String templateName,
            @RequestParam(value = "documentTitle", required = false) String documentTitle
    ) throws Exception {
        UploadedDocumentResult uploadResult = proRagDocumentIngestionService.upload(file);
        return createGeneratedFile(
                chatId,
                instruction,
                uploadResult.storedFilename(),
                outputFilename,
                outputFormat,
                templateName,
                documentTitle,
                uploadResult
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
            UploadedDocumentResult directiveFile
    ) throws Exception {
        GenerationReferenceBundle referenceBundle = proRagRetrievalService.retrieveReferenceBundle(instruction, directiveFilename);
        String generatedBody = generateContent(chatId, instruction, referenceBundle);
        DocumentOutputFormat documentOutputFormat = DocumentOutputFormat.from(outputFormat);
        DocumentTemplateContext templateContext = buildTemplateContext(
                resolveDocumentTitle(documentTitle, directiveFilename),
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

    private String generateContent(String chatId, String instruction, GenerationReferenceBundle referenceBundle) throws Exception {
        ChatClient generateChatClient = proRagConfiguration.getGenerateChatClient();
        return generateChatClient.prompt()
                .user(buildUserMessage(instruction, referenceBundle))
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .content();
    }

    private String buildUserMessage(String instruction, GenerationReferenceBundle referenceBundle) {
        if (!StringUtils.hasText(instruction)) {
            throw new IllegalArgumentException("instruction 不能为空");
        }

        List<String> mergedContents = referenceBundle.contents();
        String documentContent = mergedContents.isEmpty()
                ? "未检索到匹配材料，请根据已有要求输出基础框架，并把缺失信息标记为待补充。"
                : String.join("\n\n=========文档分隔线===========\n\n", mergedContents);
        return String.format(GENERATE_PROMPT, documentContent, instruction.trim());
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

    private String resolveDocumentTitle(String documentTitle, String directiveFilename) {
        if (StringUtils.hasText(documentTitle)) {
            return documentTitle.trim();
        }
        if (StringUtils.hasText(directiveFilename)) {
            return directiveFilename.replaceAll("\\.[^.]+$", "") + "_生成稿";
        }
        return "风控材料_" + LocalDateTime.now().format(FILE_TIME_FORMATTER);
    }
}
