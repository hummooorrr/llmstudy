package cn.wzw.llm.study.llmstudy.service.pdf;

import cn.wzw.llm.study.llmstudy.config.ParsingProperties;
import cn.wzw.llm.study.llmstudy.model.ChunkMetadataKeys;
import cn.wzw.llm.study.llmstudy.model.ChunkType;
import cn.wzw.llm.study.llmstudy.service.AssetStorageService;
import cn.wzw.llm.study.llmstudy.service.VisionModelService;
import cn.wzw.llm.study.llmstudy.splitter.SplitterFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.wzw.llm.study.llmstudy.service.DocumentIngestionPipeline;
import org.springframework.core.annotation.Order;

/**
 * PDF 入库流水线：文本抽取 + 表格候选页 VL 识别 + 内嵌图片 VL 描述。
 * 扫描件（首尝试无文本可抽取）会退化为整页 VL（支持 TABLE 标记）的处理路径。
 */
@Component
@Order(10)
@Slf4j
public class PdfIngestionPipeline implements DocumentIngestionPipeline {

    @Autowired
    private SplitterFactory splitterFactory;

    @Autowired
    private VisionModelService visionModelService;

    @Autowired
    private AssetStorageService assetStorageService;

    @Autowired
    private ParsingProperties parsingProperties;

    private static final MimeType PNG = MimeType.valueOf("image/png");
    private static final int SCAN_DPI = 150;

    @Override
    public boolean supports(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        return file.getName().toLowerCase().endsWith(".pdf");
    }

    /**
     * 对一份 PDF 生成所有类型的 chunk（text / table / image）。
     */
    @Override
    public List<Document> process(File pdfFile, String textProfile) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            int pageCount = document.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(document);

            List<Document> textChunks = extractTextChunks(document, pdfFile, textProfile);
            boolean structured = parsingProperties.isStructuredEnabled();

            List<Document> tableChunks = new ArrayList<>();
            List<Document> imageChunks = new ArrayList<>();

            if (structured && !textChunks.isEmpty()) {
                tableChunks.addAll(extractTableChunks(document, renderer, pdfFile));
                imageChunks.addAll(extractImageChunks(document, pdfFile));
            }

            if (textChunks.isEmpty()) {
                // 扫描件：整页 VL（带 TABLE 标记）
                return scannedPdfPipeline(document, renderer, pdfFile, pageCount);
            }

            List<Document> all = new ArrayList<>(textChunks);
            all.addAll(tableChunks);
            all.addAll(imageChunks);
            return all;
        }
    }

    /**
     * 文本抽取：逐页 PDFBox 抽文本 → SplitterFactory(profile) → text chunks（带 pageNumber）
     */
    private List<Document> extractTextChunks(PDDocument document, File pdfFile, String profile) throws Exception {
        int pageCount = document.getNumberOfPages();
        List<Document> perPageDocs = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        for (int i = 1; i <= pageCount; i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String pageText = stripper.getText(document);
            if (!StringUtils.hasText(pageText)) {
                continue;
            }
            Map<String, Object> metadata = baseMetadata(pdfFile);
            metadata.put(ChunkMetadataKeys.PAGE_NUMBER, i);
            metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkType.TEXT.name());
            perPageDocs.add(new Document(pageText.trim(), metadata));
        }
        if (perPageDocs.isEmpty()) {
            return List.of();
        }
        List<Document> chunks = splitterFactory.split(perPageDocs, profile);
        // 保底把 chunkType 标为 TEXT
        return chunks.stream().map(chunk -> {
            Map<String, Object> meta = new LinkedHashMap<>(chunk.getMetadata());
            meta.putIfAbsent(ChunkMetadataKeys.CHUNK_TYPE, ChunkType.TEXT.name());
            return new Document(chunk.getText(), meta);
        }).toList();
    }

    /**
     * 表格抽取：启发式识别可能含表格的页 → 渲染 → VL 产出 Markdown 表格 → 按 &lt;!--TABLE--&gt; 标记切出 table chunk。
     */
    private List<Document> extractTableChunks(PDDocument document, PDFRenderer renderer, File pdfFile) {
        int pageCount = document.getNumberOfPages();
        List<Document> result = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        try {
            stripper.setSortByPosition(true);
        } catch (Exception ignored) {
        }
        for (int i = 1; i <= pageCount; i++) {
            try {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(document);
                if (!looksLikeTablePage(pageText)) {
                    continue;
                }
                BufferedImage image = renderer.renderImageWithDPI(i - 1, SCAN_DPI);
                byte[] pngBytes = toPng(image);
                String prompt = "请识别这页 PDF 中的所有表格。对每一张表格，严格输出为 Markdown 表格，并用 <!--TABLE--> 和 <!--/TABLE--> 包裹。"
                        + "只输出表格，忽略其他正文。如果没有表格，只回复 NO_TABLE。";
                String vlOutput = visionModelService.describeImage(pngBytes, PNG, prompt);
                if (!StringUtils.hasText(vlOutput) || vlOutput.contains("NO_TABLE")) {
                    continue;
                }
                for (String tableMarkdown : splitTableBlocks(vlOutput)) {
                    if (!StringUtils.hasText(tableMarkdown)) {
                        continue;
                    }
                    Map<String, Object> metadata = baseMetadata(pdfFile);
                    metadata.put(ChunkMetadataKeys.PAGE_NUMBER, i);
                    metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkType.TABLE.name());
                    metadata.put(ChunkMetadataKeys.CHUNK_PROFILE, "pdf-table");
                    result.add(new Document(tableMarkdown.trim(), metadata));
                }
            } catch (Exception e) {
                log.warn("PDF 第 {} 页表格抽取失败: {}", i, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 内嵌图片抽取：遍历每页 XObject，命中 PDImageXObject → 存盘 + VL 描述
     */
    private List<Document> extractImageChunks(PDDocument document, File pdfFile) {
        int pageCount = document.getNumberOfPages();
        List<Document> result = new ArrayList<>();
        int imageBudget = parsingProperties.getMaxImagesPerDoc();
        for (int i = 1; i <= pageCount && imageBudget > 0; i++) {
            PDPage page = document.getPage(i - 1);
            PDResources resources = page.getResources();
            if (resources == null) {
                continue;
            }
            for (var name : resources.getXObjectNames()) {
                if (imageBudget <= 0) {
                    break;
                }
                try {
                    PDXObject xObject = resources.getXObject(name);
                    if (!(xObject instanceof PDImageXObject image)) {
                        continue;
                    }
                    if (image.getWidth() < parsingProperties.getMinImageDimension()
                            || image.getHeight() < parsingProperties.getMinImageDimension()) {
                        continue;
                    }
                    BufferedImage bi = image.getImage();
                    byte[] bytes = toPng(bi);
                    String assetPath = assetStorageService.saveImage(pdfFile.getAbsolutePath(), ".png", bytes);

                    String description;
                    try {
                        description = visionModelService.describeImage(bytes, PNG, parsingProperties.getImagePrompt());
                    } catch (Exception e) {
                        log.warn("图片 VL 描述失败（页 {}）: {}", i, e.getMessage());
                        description = "[图片，尺寸 " + image.getWidth() + "x" + image.getHeight() + "]";
                    }

                    Map<String, Object> metadata = baseMetadata(pdfFile);
                    metadata.put(ChunkMetadataKeys.PAGE_NUMBER, i);
                    metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkType.IMAGE.name());
                    metadata.put(ChunkMetadataKeys.ASSET_PATH, assetPath);
                    metadata.put(ChunkMetadataKeys.ASSET_DESCRIPTION, description);
                    metadata.put(ChunkMetadataKeys.CHUNK_PROFILE, "pdf-image");
                    result.add(new Document("[图片描述] " + description, metadata));
                    imageBudget--;
                } catch (Exception e) {
                    log.warn("PDF 第 {} 页图片抽取失败: {}", i, e.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * 扫描件 PDF 流水线：整页 VL 识别，同时识别表格（用 TABLE 标记），然后切出 text / table chunk。
     */
    private List<Document> scannedPdfPipeline(PDDocument document, PDFRenderer renderer, File pdfFile, int pageCount) throws Exception {
        List<Document> all = new ArrayList<>();
        List<Document> textPages = new ArrayList<>();
        for (int i = 1; i <= pageCount; i++) {
            log.info("扫描件识别中: {} 第 {}/{} 页", pdfFile.getName(), i, pageCount);
            BufferedImage image = renderer.renderImageWithDPI(i - 1, SCAN_DPI);
            byte[] pngBytes = toPng(image);
            String prompt = String.format(parsingProperties.getScannedPagePrompt(), i, pageCount);
            String vlOutput = visionModelService.describeImage(pngBytes, PNG, prompt);
            if (!StringUtils.hasText(vlOutput)) {
                continue;
            }

            if (parsingProperties.isStructuredEnabled()) {
                // 先把识别到的 Markdown 表格切出来
                for (String tableMarkdown : splitTableBlocks(vlOutput)) {
                    if (!StringUtils.hasText(tableMarkdown)) {
                        continue;
                    }
                    Map<String, Object> metadata = baseMetadata(pdfFile);
                    metadata.put(ChunkMetadataKeys.PAGE_NUMBER, i);
                    metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkType.TABLE.name());
                    metadata.put(ChunkMetadataKeys.SOURCE_TYPE, "scanned-pdf");
                    metadata.put(ChunkMetadataKeys.CHUNK_PROFILE, "pdf-scanned-table");
                    all.add(new Document(tableMarkdown.trim(), metadata));
                }
            }

            String cleanedText = parsingProperties.isStructuredEnabled()
                    ? stripTableBlocks(vlOutput)
                    : vlOutput.replaceAll("(?s)<!--/?TABLE-->", " ");
            if (StringUtils.hasText(cleanedText)) {
                Map<String, Object> metadata = baseMetadata(pdfFile);
                metadata.put(ChunkMetadataKeys.PAGE_NUMBER, i);
                metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkType.TEXT.name());
                metadata.put(ChunkMetadataKeys.SOURCE_TYPE, "scanned-pdf");
                textPages.add(new Document(cleanedText.trim(), metadata));
            }
        }

        if (!textPages.isEmpty()) {
            List<Document> textChunks = splitterFactory.split(textPages, SplitterFactory.PROFILE_PDF_SCANNED);
            textChunks.forEach(chunk -> {
                Map<String, Object> meta = new LinkedHashMap<>(chunk.getMetadata());
                meta.putIfAbsent(ChunkMetadataKeys.CHUNK_TYPE, ChunkType.TEXT.name());
                all.add(new Document(chunk.getText(), meta));
            });
        }
        return all;
    }

    /**
     * 简单启发式：页面中连续至少 3 行，每行含 2 处及以上 2+ 空白间隔 → 疑似表格
     */
    private boolean looksLikeTablePage(String pageText) {
        if (!StringUtils.hasText(pageText)) {
            return false;
        }
        String[] lines = pageText.split("\\r?\\n");
        int consecutive = 0;
        int maxConsecutive = 0;
        for (String line : lines) {
            if (line == null) {
                consecutive = 0;
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.length() < 4) {
                consecutive = 0;
                continue;
            }
            // 统计内部多空格或制表符分隔列数
            int cols = 0;
            boolean inGap = false;
            int gapWidth = 0;
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == '\t' || c == ' ' || c == '\u3000') {
                    gapWidth++;
                    inGap = true;
                } else {
                    if (inGap && gapWidth >= 2) {
                        cols++;
                    }
                    inGap = false;
                    gapWidth = 0;
                }
            }
            if (cols >= 2) {
                consecutive++;
                maxConsecutive = Math.max(maxConsecutive, consecutive);
            } else {
                consecutive = 0;
            }
        }
        return maxConsecutive >= 3;
    }

    private List<String> splitTableBlocks(String vlOutput) {
        List<String> blocks = new ArrayList<>();
        int from = 0;
        while (true) {
            int start = vlOutput.indexOf("<!--TABLE-->", from);
            if (start < 0) {
                break;
            }
            int end = vlOutput.indexOf("<!--/TABLE-->", start);
            if (end < 0) {
                break;
            }
            String block = vlOutput.substring(start + "<!--TABLE-->".length(), end).trim();
            if (!block.isEmpty()) {
                blocks.add(block);
            }
            from = end + "<!--/TABLE-->".length();
        }
        return blocks;
    }

    private String stripTableBlocks(String vlOutput) {
        return vlOutput.replaceAll("(?s)<!--TABLE-->.*?<!--/TABLE-->", " ").trim();
    }

    private byte[] toPng(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    private Map<String, Object> baseMetadata(File file) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChunkMetadataKeys.FILENAME, file.getName());
        metadata.put(ChunkMetadataKeys.FILE_PATH, file.getAbsolutePath());
        return metadata;
    }
}
