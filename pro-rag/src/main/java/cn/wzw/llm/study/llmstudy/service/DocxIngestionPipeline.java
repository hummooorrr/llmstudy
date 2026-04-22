package cn.wzw.llm.study.llmstudy.service;

import cn.wzw.llm.study.llmstudy.config.ParsingProperties;
import cn.wzw.llm.study.llmstudy.model.ChunkMetadataKeys;
import cn.wzw.llm.study.llmstudy.model.ChunkType;
import cn.wzw.llm.study.llmstudy.splitter.SplitterFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DOCX 结构化入库流水线。
 * <ul>
 *   <li>按 body 元素顺序切分，段落聚合成 text chunk（就近标题作 sectionPath）</li>
 *   <li>XWPFTable → 独立 Markdown 表格 chunk</li>
 *   <li>XWPFPictureData → 落盘 + VL 描述 → image chunk</li>
 * </ul>
 * 对于 .doc 旧格式，回退走 {@link cn.wzw.llm.study.llmstudy.splitter.WordHeaderTextSplitter}
 * 原有纯文本分块路径，不做结构化拆分。
 */
@Component
@Slf4j
public class DocxIngestionPipeline {

    @Autowired
    private SplitterFactory splitterFactory;

    @Autowired
    private VisionModelService visionModelService;

    @Autowired
    private AssetStorageService assetStorageService;

    @Autowired
    private ParsingProperties parsingProperties;

    private static final MimeType PNG = MimeType.valueOf("image/png");
    private static final MimeType JPEG = MimeType.valueOf("image/jpeg");

    public List<Document> process(File docFile, String profile) throws Exception {
        String lowerName = docFile.getName().toLowerCase();
        if (lowerName.endsWith(".doc")) {
            return processLegacyDoc(docFile, profile);
        }

        try (XWPFDocument document = new XWPFDocument(new FileInputStream(docFile))) {
            List<Document> result = new ArrayList<>();
            List<Document> sectionDocs = collectSectionTextDocs(document, docFile);
            if (!sectionDocs.isEmpty()) {
                List<Document> textChunks = splitterFactory.split(sectionDocs, profile);
                textChunks.forEach(chunk -> {
                    Map<String, Object> meta = new LinkedHashMap<>(chunk.getMetadata());
                    meta.putIfAbsent(ChunkMetadataKeys.CHUNK_TYPE, ChunkType.TEXT.name());
                    result.add(new Document(chunk.getText(), meta));
                });
            }

            if (parsingProperties.isStructuredEnabled()) {
                result.addAll(extractTableChunks(document, docFile));
                result.addAll(extractImageChunks(document, docFile));
            }
            return result;
        }
    }

    /**
     * 顺序遍历 body：
     * - 段落：聚合到当前 section，标题行切新 section
     * - 表格：单独产出 table chunk（这里只生成 section 级 text 文档，表格由 extractTableChunks 处理）
     */
    private List<Document> collectSectionTextDocs(XWPFDocument document, File docFile) {
        List<Document> sections = new ArrayList<>();
        List<String> currentLines = new ArrayList<>();
        String[] headingStack = new String[10];
        String currentSectionPath = "";

        for (IBodyElement element : document.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                String text = paragraph.getText() == null ? "" : paragraph.getText().trim();
                if (text.isEmpty()) {
                    continue;
                }
                Integer level = extractHeadingLevel(paragraph);
                if (level != null) {
                    if (!currentLines.isEmpty()) {
                        sections.add(buildSectionDoc(docFile, String.join("\n", currentLines), currentSectionPath));
                        currentLines.clear();
                    }
                    headingStack[Math.min(level, headingStack.length - 1)] = text;
                    for (int i = level + 1; i < headingStack.length; i++) {
                        headingStack[i] = null;
                    }
                    currentSectionPath = joinHeadingStack(headingStack);
                    currentLines.add(text);
                } else {
                    currentLines.add(text);
                }
            }
        }
        if (!currentLines.isEmpty()) {
            sections.add(buildSectionDoc(docFile, String.join("\n", currentLines), currentSectionPath));
        }
        return sections;
    }

    private Document buildSectionDoc(File docFile, String text, String sectionPath) {
        Map<String, Object> metadata = baseMetadata(docFile);
        if (StringUtils.hasText(sectionPath)) {
            metadata.put(ChunkMetadataKeys.SECTION_PATH, sectionPath);
        }
        return new Document(text, metadata);
    }

    private String joinHeadingStack(String[] headingStack) {
        List<String> parts = new ArrayList<>();
        for (String heading : headingStack) {
            if (StringUtils.hasText(heading)) {
                parts.add(heading);
            }
        }
        return String.join(" / ", parts);
    }

    private Integer extractHeadingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            return null;
        }
        // Word 内置标题样式通常是 "Heading1" / "Heading 1" / "标题 1" 等
        String normalized = style.replaceAll("\\s+", "").toLowerCase();
        if (normalized.startsWith("heading")) {
            String tail = normalized.substring("heading".length());
            try {
                return Integer.parseInt(tail);
            } catch (NumberFormatException ignored) {
            }
        }
        if (normalized.startsWith("标题")) {
            String tail = normalized.substring(2);
            try {
                return Integer.parseInt(tail);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private List<Document> extractTableChunks(XWPFDocument document, File docFile) {
        List<Document> result = new ArrayList<>();
        int tableIndex = 0;
        for (XWPFTable table : document.getTables()) {
            tableIndex++;
            String markdown = tableToMarkdown(table);
            if (!StringUtils.hasText(markdown)) {
                continue;
            }
            Map<String, Object> metadata = baseMetadata(docFile);
            metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkType.TABLE.name());
            metadata.put(ChunkMetadataKeys.CHUNK_PROFILE, "docx-table");
            metadata.put("tableIndex", tableIndex);
            result.add(new Document(markdown, metadata));
        }
        return result;
    }

    private String tableToMarkdown(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int colCount = rows.get(0).getTableCells().size();
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = rows.get(r);
            List<XWPFTableCell> cells = row.getTableCells();
            sb.append("| ");
            for (int c = 0; c < colCount; c++) {
                String cellText = c < cells.size() ? normaliseCell(cells.get(c).getText()) : "";
                sb.append(cellText);
                sb.append(" | ");
            }
            sb.append('\n');
            if (r == 0) {
                sb.append("| ");
                for (int c = 0; c < colCount; c++) {
                    sb.append("--- | ");
                }
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String normaliseCell(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").replace("|", "\\|").trim();
    }

    private List<Document> extractImageChunks(XWPFDocument document, File docFile) {
        List<Document> result = new ArrayList<>();
        int imageBudget = parsingProperties.getMaxImagesPerDoc();
        int index = 0;
        for (XWPFPictureData picture : document.getAllPictures()) {
            if (imageBudget <= 0) {
                break;
            }
            index++;
            try {
                byte[] bytes = picture.getData();
                if (bytes == null || bytes.length == 0) {
                    continue;
                }
                String extension = "." + (picture.suggestFileExtension() == null ? "png"
                        : picture.suggestFileExtension().toLowerCase());
                String assetPath = assetStorageService.saveImage(docFile.getAbsolutePath(), extension, bytes);

                String description;
                try {
                    MimeType mimeType = extension.endsWith(".jpg") || extension.endsWith(".jpeg") ? JPEG : PNG;
                    description = visionModelService.describeImage(bytes, mimeType, parsingProperties.getImagePrompt());
                } catch (Exception e) {
                    log.warn("DOCX 图片 VL 描述失败（#{}）: {}", index, e.getMessage());
                    description = "[嵌入图片，大小 " + bytes.length + " bytes]";
                }

                Map<String, Object> metadata = baseMetadata(docFile);
                metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkType.IMAGE.name());
                metadata.put(ChunkMetadataKeys.ASSET_PATH, assetPath);
                metadata.put(ChunkMetadataKeys.ASSET_DESCRIPTION, description);
                metadata.put(ChunkMetadataKeys.CHUNK_PROFILE, "docx-image");
                metadata.put("imageIndex", index);
                result.add(new Document("[图片描述] " + description, metadata));
                imageBudget--;
            } catch (Exception e) {
                log.warn("DOCX 第 {} 张图片抽取失败: {}", index, e.getMessage());
            }
        }
        return result;
    }

    private Map<String, Object> baseMetadata(File file) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChunkMetadataKeys.FILENAME, file.getName());
        metadata.put(ChunkMetadataKeys.FILE_PATH, file.getAbsolutePath());
        return metadata;
    }

    /**
     * .doc 旧格式直接复用已有 WordHeaderTextSplitter，不做结构化抽取。
     */
    private List<Document> processLegacyDoc(File docFile, String profile) throws Exception {
        Map<String, Object> metadata = baseMetadata(docFile);
        metadata.put("wordInputStream", Files.readAllBytes(docFile.toPath()));
        List<Document> chunks = splitterFactory.split(List.of(new Document("", metadata)), profile);
        List<Document> result = new ArrayList<>(chunks.size());
        for (Document chunk : chunks) {
            Map<String, Object> meta = new LinkedHashMap<>(chunk.getMetadata());
            meta.remove("wordInputStream");
            meta.putIfAbsent(ChunkMetadataKeys.CHUNK_TYPE, ChunkType.TEXT.name());
            result.add(new Document(chunk.getText(), meta));
        }
        return result;
    }
}
