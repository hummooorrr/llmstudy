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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

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
     * 表格和图片的 VL 调用通过虚拟线程 + Semaphore 并发执行，大幅缩短入库耗时。
     */
    @Override
    public List<Document> process(File pdfFile, String textProfile) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            int pageCount = document.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(document);

            // Step 1: 文本抽取（CPU bound，同步完成），同时收集每页原文供表格检测复用
            Map<Integer, String> pageTexts = new LinkedHashMap<>();
            List<Document> textChunks = extractTextChunks(document, pdfFile, textProfile, pageTexts);
            boolean structured = parsingProperties.isStructuredEnabled();

            if (textChunks.isEmpty()) {
                // 扫描件：整页 VL（带 TABLE 标记）
                return scannedPdfPipeline(document, renderer, pdfFile, pageCount);
            }

            if (!structured) {
                return new ArrayList<>(textChunks);
            }

            // Step 2: 表格抽取 + 图片抽取并行（都是 VL 调用，I/O bound）
            int maxConcurrency = Math.max(1, parsingProperties.getVisionMaxConcurrency());
            Semaphore visionSemaphore = new Semaphore(maxConcurrency);
            try (ExecutorService vlExecutor = Executors.newVirtualThreadPerTaskExecutor()) {

                CompletableFuture<List<Document>> tableFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        visionSemaphore.acquire();
                        try {
                            return extractTableChunks(document, renderer, pdfFile, pageTexts);
                        } finally {
                            visionSemaphore.release();
                        }
                    } catch (Exception e) {
                        log.warn("PDF 表格并发抽取失败: {}", e.getMessage());
                        return List.of();
                    }
                }, vlExecutor);

                CompletableFuture<List<Document>> imageFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        visionSemaphore.acquire();
                        try {
                            return extractImageChunks(document, pdfFile);
                        } finally {
                            visionSemaphore.release();
                        }
                    } catch (Exception e) {
                        log.warn("PDF 图片并发抽取失败: {}", e.getMessage());
                        return List.of();
                    }
                }, vlExecutor);

                List<Document> tableChunks = tableFuture.get();
                List<Document> imageChunks = imageFuture.get();

                List<Document> all = new ArrayList<>(textChunks);
                all.addAll(tableChunks);
                all.addAll(imageChunks);
                return all;
            }
        }
    }

    /**
     * 文本抽取：逐页 PDFBox 抽文本 → SplitterFactory(profile) → text chunks（带 pageNumber）。
     * 同时将每页原文收集到 {@code pageTexts} 供表格检测复用，避免重复调用 PDFTextStripper。
     */
    private List<Document> extractTextChunks(PDDocument document, File pdfFile, String profile,
                                             Map<Integer, String> pageTexts) throws Exception {
        int pageCount = document.getNumberOfPages();
        List<Document> perPageDocs = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        for (int i = 1; i <= pageCount; i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String pageText = stripper.getText(document);
            pageTexts.put(i, pageText);
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
     * 表格抽取：复用已抽取的页面文本做启发式判断 → 渲染候选页 → VL 产出 Markdown 表格 → table chunk。
     * 候选页的 VL 调用通过虚拟线程 + Semaphore 并发执行。
     */
    private List<Document> extractTableChunks(PDDocument document, PDFRenderer renderer,
                                              File pdfFile, Map<Integer, String> pageTexts) {
        int pageCount = document.getNumberOfPages();

        // 复用已有 pageTexts 筛选候选页，不再重复调用 PDFTextStripper
        List<Integer> candidatePages = new ArrayList<>();
        for (int i = 1; i <= pageCount; i++) {
            String pageText = pageTexts.get(i);
            if (looksLikeTablePage(pageText)) {
                candidatePages.add(i);
            }
        }
        if (candidatePages.isEmpty()) {
            return List.of();
        }

        // VL 并发抽取表格
        int maxConcurrency = Math.max(1, parsingProperties.getVisionMaxConcurrency());
        Semaphore semaphore = new Semaphore(maxConcurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<Document>>> futures = new ArrayList<>();
            for (int pageNum : candidatePages) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            return extractTablesFromPage(renderer, pageNum, pdfFile);
                        } finally {
                            semaphore.release();
                        }
                    } catch (Exception e) {
                        log.warn("PDF 第 {} 页表格抽取失败: {}", pageNum, e.getMessage());
                        return List.<Document>of();
                    }
                }, executor));
            }
            return futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .toList();
        }
    }

    /**
     * 单页表格 VL 识别：渲染页面图片 → 调 VL → 解析 <!--TABLE--> 块。
     */
    private List<Document> extractTablesFromPage(PDFRenderer renderer, int pageNum, File pdfFile) throws Exception {
        BufferedImage image = renderer.renderImageWithDPI(pageNum - 1, SCAN_DPI);
        byte[] pngBytes = toPng(image);
        String prompt = "请识别这页 PDF 中的所有表格。对每一张表格，严格输出为 Markdown 表格，并用 <!--TABLE--> 和 <!--/TABLE--> 包裹。"
                + "只输出表格，忽略其他正文。如果没有表格，只回复 NO_TABLE。";
        String vlOutput = visionModelService.describeImage(pngBytes, PNG, prompt);
        if (!StringUtils.hasText(vlOutput) || vlOutput.contains("NO_TABLE")) {
            return List.of();
        }
        List<Document> result = new ArrayList<>();
        for (String tableMarkdown : splitTableBlocks(vlOutput)) {
            if (!StringUtils.hasText(tableMarkdown)) {
                continue;
            }
            Map<String, Object> metadata = baseMetadata(pdfFile);
            metadata.put(ChunkMetadataKeys.PAGE_NUMBER, pageNum);
            metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkType.TABLE.name());
            metadata.put(ChunkMetadataKeys.CHUNK_PROFILE, "pdf-table");
            result.add(new Document(tableMarkdown.trim(), metadata));
        }
        return result;
    }

    /**
     * 内嵌图片抽取：遍历每页 XObject，命中 PDImageXObject → 存盘 + VL 描述。
     * 图片落盘同步完成，VL 描述通过虚拟线程 + Semaphore 并发调用。
     */
    private List<Document> extractImageChunks(PDDocument document, File pdfFile) {
        int pageCount = document.getNumberOfPages();
        int imageBudget = parsingProperties.getMaxImagesPerDoc();

        // Phase 1: 同步收集所有候选图片（从 PDF 中提取 + 落盘，不调用 VL）
        record ImageCandidate(byte[] pngBytes, String assetPath, int width, int height, int pageNumber) {}
        List<ImageCandidate> candidates = new ArrayList<>();

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
                    candidates.add(new ImageCandidate(bytes, assetPath, image.getWidth(), image.getHeight(), i));
                    imageBudget--;
                } catch (Exception e) {
                    log.warn("PDF 第 {} 页图片抽取失败: {}", i, e.getMessage());
                }
            }
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        // Phase 2: VL 描述并发
        int maxConcurrency = Math.max(1, parsingProperties.getVisionMaxConcurrency());
        Semaphore semaphore = new Semaphore(maxConcurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Document>> futures = new ArrayList<>();
            for (ImageCandidate c : candidates) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    String description;
                    try {
                        semaphore.acquire();
                        try {
                            description = visionModelService.describeImage(
                                    c.pngBytes(), PNG, parsingProperties.getImagePrompt());
                        } finally {
                            semaphore.release();
                        }
                    } catch (Exception e) {
                        log.warn("图片 VL 描述失败（页 {}）: {}", c.pageNumber(), e.getMessage());
                        description = "[图片，尺寸 " + c.width() + "x" + c.height() + "]";
                    }
                    Map<String, Object> metadata = baseMetadata(pdfFile);
                    metadata.put(ChunkMetadataKeys.PAGE_NUMBER, c.pageNumber());
                    metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkType.IMAGE.name());
                    metadata.put(ChunkMetadataKeys.ASSET_PATH, c.assetPath());
                    metadata.put(ChunkMetadataKeys.ASSET_DESCRIPTION, description);
                    metadata.put(ChunkMetadataKeys.CHUNK_PROFILE, "pdf-image");
                    return new Document("[图片描述] " + description, metadata);
                }, executor));
            }
            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        }
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
