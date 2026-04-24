package cn.wzw.llm.study.llmstudy.output;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 文档模板渲染与输出服务。
 * 使用 FreeMarker 渲染 Markdown 模板，DOCX 使用 JSON block + FreeMarker 内联变量替换。
 */
@Service
public class ProRagTemplateOutputService {

    private static final String DEFAULT_TEMPLATE = "default";

    @Value("${pro-rag.generated-dir:./pro-rag-generated}")
    private String generatedDir;

    private final Configuration freemarkerConfig;
    private final ResourceLoader resourceLoader;

    public ProRagTemplateOutputService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.freemarkerConfig = new Configuration(Configuration.VERSION_2_3_34);
        this.freemarkerConfig.setClassLoaderForTemplateLoading(
                Thread.currentThread().getContextClassLoader(), "/templates/pro-rag");
        this.freemarkerConfig.setDefaultEncoding("UTF-8");
    }

    public RenderedTemplateOutput renderAndSave(
            DocumentTemplateContext context,
            DocumentOutputFormat outputFormat,
            String templateName,
            String outputFilename
    ) throws Exception {
        String resolvedTemplateName = StringUtils.hasText(templateName) ? templateName.trim() : DEFAULT_TEMPLATE;
        String previewContent = renderMarkdown(context, resolvedTemplateName);

        Path outputDirectory = Paths.get(generatedDir);
        Files.createDirectories(outputDirectory);
        Path outputPath = resolveUniqueOutputPath(outputDirectory,
                resolveOutputFilename(outputFilename, context.documentTitle(), outputFormat));

        if (outputFormat == DocumentOutputFormat.MARKDOWN) {
            Files.writeString(outputPath, previewContent, StandardCharsets.UTF_8);
        } else {
            writeDocxFile(outputPath, context, resolvedTemplateName);
        }

        return new RenderedTemplateOutput(
                resolvedTemplateName,
                outputFormat.code(),
                outputPath.getFileName().toString(),
                outputPath.toAbsolutePath().toString(),
                previewContent
        );
    }

    private String renderMarkdown(DocumentTemplateContext context, String templateName) throws Exception {
        Map<String, Object> model = Map.of(
                "documentTitle", context.documentTitle(),
                "generatedAt", context.generatedAt(),
                "directiveFilename", context.directiveFilename(),
                "instruction", context.instruction(),
                "referenceFiles", context.referenceFiles(),
                "body", context.body()
        );

        return processTemplate("markdown/" + templateName + ".ftl",
                "markdown/" + DEFAULT_TEMPLATE + ".ftl", model);
    }

    private void writeDocxFile(Path outputPath, DocumentTemplateContext context, String templateName) throws Exception {
        String layoutJson = loadTemplateContent("docx/" + templateName + ".json",
                "docx/" + DEFAULT_TEMPLATE + ".json");
        JSONArray blocks = JSON.parseArray(layoutJson);

        try (XWPFDocument document = new XWPFDocument()) {
            for (int i = 0; i < blocks.size(); i++) {
                JSONObject block = blocks.getJSONObject(i);
                String type = block.getString("type");
                if (!StringUtils.hasText(type)) {
                    continue;
                }
                renderDocxBlock(document, type.trim(), block, context);
            }

            try (var outputStream = Files.newOutputStream(outputPath)) {
                document.write(outputStream);
            }
        }
    }

    private void renderDocxBlock(XWPFDocument document, String type, JSONObject block, DocumentTemplateContext context) {
        switch (type) {
            case "title" -> addStyledParagraph(document, renderInline(block.getString("text"), context),
                    18, true, ParagraphAlignment.CENTER);
            case "heading1" -> addStyledParagraph(document, renderInline(block.getString("text"), context),
                    14, true, ParagraphAlignment.LEFT);
            case "meta" -> addParagraphLines(document,
                    safeValue(block.getString("label")) + "：" + resolvePlaceholderText(block.getString("placeholder"), context),
                    false);
            case "placeholder" -> addParagraphLines(document, resolvePlaceholderText(block.getString("placeholder"), context), false);
            case "blank" -> document.createParagraph();
            default -> {
            }
        }
    }

    private void addStyledParagraph(XWPFDocument document, String text, int fontSize, boolean bold, ParagraphAlignment alignment) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(alignment);
        XWPFRun run = paragraph.createRun();
        run.setBold(bold);
        run.setFontSize(fontSize);
        run.setText(safeValue(text));
    }

    private void addParagraphLines(XWPFDocument document, String content, boolean bold) {
        if (!StringUtils.hasText(content)) {
            document.createParagraph();
            return;
        }

        String[] lines = content.replace("\r\n", "\n").split("\n", -1);
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                document.createParagraph();
                continue;
            }

            if (line.startsWith("# ")) {
                addStyledParagraph(document, line.substring(2).trim(), 16, true, ParagraphAlignment.LEFT);
                continue;
            }
            if (line.startsWith("## ")) {
                addStyledParagraph(document, line.substring(3).trim(), 14, true, ParagraphAlignment.LEFT);
                continue;
            }

            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setBold(bold);
            run.setFontSize(11);
            run.setText(line.startsWith("- ") ? "• " + line.substring(2).trim() : line);
        }
    }

    /**
     * 通过 FreeMarker 渲染类路径模板，优先加载主模板，不存在则回退到默认模板。
     */
    private String processTemplate(String primaryPath, String fallbackPath, Map<String, Object> model) throws Exception {
        Template template;
        try {
            template = freemarkerConfig.getTemplate(primaryPath);
        } catch (Exception e) {
            template = freemarkerConfig.getTemplate(fallbackPath);
        }
        StringWriter writer = new StringWriter();
        template.process(model, writer);
        return writer.toString();
    }

    /**
     * 加载原始模板文件内容（不会经过 FreeMarker 渲染），用于 JSON 布局文件。
     */
    private String loadTemplateContent(String primaryPath, String fallbackPath) throws Exception {
        String classPath = "classpath:templates/pro-rag/" + primaryPath;
        Resource resource = resourceLoader.getResource(classPath);
        if (!resource.exists()) {
            classPath = "classpath:templates/pro-rag/" + fallbackPath;
            resource = resourceLoader.getResource(classPath);
        }
        try (var inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String renderInline(String templateText, DocumentTemplateContext context) {
        if (!StringUtils.hasText(templateText)) {
            return "";
        }
        return templateText
                .replace("${documentTitle}", safeValue(context.documentTitle()))
                .replace("${generatedAt}", safeValue(context.generatedAt()))
                .replace("${directiveFilename}", safeValue(context.directiveFilename()));
    }

    private String resolvePlaceholderText(String placeholder, DocumentTemplateContext context) {
        if (!StringUtils.hasText(placeholder)) {
            return "";
        }
        return switch (placeholder.trim()) {
            case "documentTitle" -> safeValue(context.documentTitle());
            case "generatedAt" -> safeValue(context.generatedAt());
            case "directiveFilename" -> safeValue(context.directiveFilename());
            case "instruction" -> safeValue(context.instruction());
            case "referenceFiles" -> safeValue(context.referenceFiles());
            case "body" -> safeValue(context.body());
            default -> "";
        };
    }

    private String resolveOutputFilename(String outputFilename, String documentTitle, DocumentOutputFormat outputFormat) {
        String baseName = StringUtils.hasText(outputFilename) ? outputFilename.trim() : safeValue(documentTitle);
        if (!StringUtils.hasText(baseName)) {
            baseName = "风控材料";
        }

        String sanitized = baseName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        sanitized = Paths.get(sanitized).getFileName().toString();
        if (!sanitized.endsWith(outputFormat.extension())) {
            sanitized = sanitized.replaceAll("\\.[^.]+$", "") + outputFormat.extension();
        }
        return sanitized;
    }

    private Path resolveUniqueOutputPath(Path directory, String filename) throws Exception {
        Path candidate = directory.resolve(filename);
        if (!Files.exists(candidate)) {
            return candidate;
        }

        int dotIndex = filename.lastIndexOf('.');
        String baseName = dotIndex >= 0 ? filename.substring(0, dotIndex) : filename;
        String extension = dotIndex >= 0 ? filename.substring(dotIndex) : "";

        int counter = 1;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(baseName + "_" + counter + extension);
            counter++;
        }
        return candidate;
    }

    private String safeValue(String value) {
        return StringUtils.hasText(value) ? value : "无";
    }
}
