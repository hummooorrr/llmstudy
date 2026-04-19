package cn.wzw.llm.study.llmstudy.output;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class ProRagTemplateOutputService {

    private static final String DEFAULT_TEMPLATE = "default";

    @Value("${pro-rag.generated-dir:./pro-rag-generated}")
    private String generatedDir;

    private final ResourceLoader resourceLoader;

    public ProRagTemplateOutputService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public RenderedTemplateOutput renderAndSave(
            DocumentTemplateContext context,
            DocumentOutputFormat outputFormat,
            String templateName,
            String outputFilename
    ) throws Exception {
        String resolvedTemplateName = StringUtils.hasText(templateName) ? templateName.trim() : DEFAULT_TEMPLATE;
        String previewContent = renderMarkdownTemplate(context, resolvedTemplateName);

        Path outputDirectory = Paths.get(generatedDir);
        Files.createDirectories(outputDirectory);
        Path outputPath = resolveUniqueOutputPath(outputDirectory, resolveOutputFilename(outputFilename, context.documentTitle(), outputFormat));

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

    private String renderMarkdownTemplate(DocumentTemplateContext context, String templateName) throws Exception {
        String template = readTemplate("classpath:templates/pro-rag/markdown/" + templateName + ".md",
                "classpath:templates/pro-rag/markdown/" + DEFAULT_TEMPLATE + ".md");

        return template
                .replace("${documentTitle}", safeValue(context.documentTitle()))
                .replace("${generatedAt}", safeValue(context.generatedAt()))
                .replace("${directiveFilename}", safeValue(context.directiveFilename()))
                .replace("${instruction}", safeValue(context.instruction()))
                .replace("${referenceFiles}", safeValue(context.referenceFiles()))
                .replace("${body}", safeValue(context.body()));
    }

    private void writeDocxFile(Path outputPath, DocumentTemplateContext context, String templateName) throws Exception {
        String layoutJson = readTemplate("classpath:templates/pro-rag/docx/" + templateName + ".json",
                "classpath:templates/pro-rag/docx/" + DEFAULT_TEMPLATE + ".json");
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
            case "title" -> addStyledParagraph(document, resolveInlinePlaceholders(block.getString("text"), context),
                    18, true, ParagraphAlignment.CENTER);
            case "heading1" -> addStyledParagraph(document, resolveInlinePlaceholders(block.getString("text"), context),
                    14, true, ParagraphAlignment.LEFT);
            case "meta" -> addParagraphLines(document,
                    safeValue(block.getString("label")) + "：" + resolvePlaceholder(block.getString("placeholder"), context),
                    false);
            case "placeholder" -> addParagraphLines(document, resolvePlaceholder(block.getString("placeholder"), context), false);
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

    private String resolveInlinePlaceholders(String templateText, DocumentTemplateContext context) {
        if (!StringUtils.hasText(templateText)) {
            return "";
        }
        return templateText
                .replace("${documentTitle}", safeValue(context.documentTitle()))
                .replace("${generatedAt}", safeValue(context.generatedAt()))
                .replace("${directiveFilename}", safeValue(context.directiveFilename()));
    }

    private String resolvePlaceholder(String placeholder, DocumentTemplateContext context) {
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

    private String readTemplate(String primaryLocation, String fallbackLocation) throws Exception {
        Resource resource = resourceLoader.getResource(primaryLocation);
        if (!resource.exists()) {
            resource = resourceLoader.getResource(fallbackLocation);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
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
