package cn.wzw.llm.study.llmstudy.service;

import cn.wzw.llm.study.llmstudy.dto.ingestion.UploadedDocumentResult;
import cn.wzw.llm.study.llmstudy.model.EsDocumentChunk;
import cn.wzw.llm.study.llmstudy.reader.DocumentReaderStrategy;
import cn.wzw.llm.study.llmstudy.splitter.MarkdownHeaderTextSplitter;
import cn.wzw.llm.study.llmstudy.splitter.OverlapParagraphTextSplitter;
import cn.wzw.llm.study.llmstudy.splitter.WordHeaderTextSplitter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * 文件接收、分片和入库服务。
 * 把上传落盘、切分和双写存储集中到一个地方，便于复用到"上传即生成"等场景。
 */
@Service
@Slf4j
public class ProRagDocumentIngestionService {

    @Value("${pro-rag.upload-dir:./pro-rag-files}")
    private String uploadDir;

    @Autowired
    private DocumentReaderStrategySelector documentReaderStrategySelector;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private ProRagElasticSearchService proRagElasticSearchService;

    @Autowired
    private VisionModelService visionModelService;

    public UploadedDocumentResult upload(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        Path uploadPath = Paths.get(uploadDir).toAbsolutePath();
        Files.createDirectories(uploadPath);

        String originalFilename = StringUtils.cleanPath(resolveOriginalFilename(file));
        Path storedPath = resolveUniquePath(uploadPath, originalFilename);
        file.transferTo(storedPath.toFile());

        List<Document> chunks = splitDocuments(storedPath.toFile());
        if (CollectionUtils.isEmpty(chunks)) {
            throw new IllegalArgumentException("文件解析后没有可入库内容: " + originalFilename);
        }

        List<EsDocumentChunk> esDocs = chunks.stream().map(doc -> {
            EsDocumentChunk es = new EsDocumentChunk();
            es.setId(doc.getId());
            es.setContent(doc.getText());
            es.setMetadata(doc.getMetadata());
            return es;
        }).toList();

        // 先写向量库，再写 ES；任一步骤失败则记录需补录
        boolean vectorStored = false;
        boolean esStored = false;
        try {
            embeddingService.embedAndStore(chunks);
            vectorStored = true;
            proRagElasticSearchService.bulkIndex(esDocs);
            esStored = true;
        } catch (Exception e) {
            log.error("文档入库失败: {}", e.getMessage(), e);
            if (vectorStored && !esStored) {
                log.error("向量库已写入但 ES 索引失败，需手动补录 {} 条 chunk，文件: {}",
                        esDocs.size(), originalFilename);
            }
            throw e;
        }

        return new UploadedDocumentResult(
                originalFilename,
                storedPath.getFileName().toString(),
                storedPath.toAbsolutePath().toString(),
                chunks.size(),
                "success"
        );
    }

    private String resolveOriginalFilename(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            throw new IllegalArgumentException("无法识别上传文件名");
        }
        return Paths.get(originalFilename).getFileName().toString();
    }

    private Path resolveUniquePath(Path uploadPath, String originalFilename) throws Exception {
        String sanitizedFilename = sanitizeFilename(originalFilename);
        Path targetPath = uploadPath.resolve(sanitizedFilename);
        if (!Files.exists(targetPath)) {
            return targetPath;
        }

        int dotIndex = sanitizedFilename.lastIndexOf('.');
        String baseName = dotIndex >= 0 ? sanitizedFilename.substring(0, dotIndex) : sanitizedFilename;
        String extension = dotIndex >= 0 ? sanitizedFilename.substring(dotIndex) : "";

        int counter = 1;
        while (Files.exists(targetPath)) {
            targetPath = uploadPath.resolve(baseName + "_" + counter + extension);
            counter++;
        }
        return targetPath;
    }

    private String sanitizeFilename(String filename) {
        String sanitized = filename.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (!StringUtils.hasText(sanitized)) {
            throw new IllegalArgumentException("文件名不合法");
        }
        return sanitized;
    }

    private List<Document> splitDocuments(File localFile) throws Exception {
        String fileName = localFile.getName().toLowerCase();

        if (fileName.endsWith(".pdf")) {
            List<Document> chunks = splitPdf(localFile);
            if (CollectionUtils.isNotEmpty(chunks)) {
                return chunks;
            }
        }

        if (fileName.endsWith(".md")) {
            String rawText = Files.readString(localFile.toPath());
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("# ", "h1");
            headers.put("## ", "h2");
            headers.put("### ", "h3");
            MarkdownHeaderTextSplitter splitter = new MarkdownHeaderTextSplitter(headers, false, false, true);
            Map<String, Object> metadata = sourceMetadata(localFile);
            return splitter.apply(List.of(new Document(rawText, metadata)));
        }

        if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
            Map<String, Object> metadata = sourceMetadata(localFile);
            metadata.put("wordInputStream", Files.readAllBytes(localFile.toPath()));
            WordHeaderTextSplitter splitter = new WordHeaderTextSplitter(null, false, false, true, 500, 100);
            return splitter.apply(List.of(new Document("", metadata)));
        }

        List<Document> documents = clean(documentReaderStrategySelector.read(localFile));
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(400, 100);
        return splitter.apply(documents);
    }

    private Map<String, Object> sourceMetadata(File file) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("filename", file.getName());
        metadata.put("filePath", file.getAbsolutePath());
        return metadata;
    }

    /**
     * PDF 处理：先尝试文本提取，如果提取为空（扫描件）则用视觉模型逐页识别
     */
    private List<Document> splitPdf(File localFile) throws Exception {
        List<Document> documents = clean(documentReaderStrategySelector.read(localFile));
        if (CollectionUtils.isNotEmpty(documents)) {
            OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(400, 100);
            return splitter.apply(documents);
        }
        // 文本提取为空，按扫描件处理
        log.info("PDF 文本提取为空，按扫描件处理: {}", localFile.getName());
        return ocrPdfPages(localFile);
    }

    /**
     * 将 PDF 每页渲染为图片，调用视觉模型识别文字
     */
    private List<Document> ocrPdfPages(File pdfFile) throws Exception {
        org.apache.pdfbox.pdmodel.PDDocument pdDocument = org.apache.pdfbox.Loader.loadPDF(pdfFile);
        try {
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(pdDocument);
            int pageCount = pdDocument.getNumberOfPages();
            java.util.List<Document> allDocs = new java.util.ArrayList<>();

            for (int i = 0; i < pageCount; i++) {
                log.info("扫描件识别中: {} 第 {}/{} 页", pdfFile.getName(), i + 1, pageCount);
                BufferedImage image = renderer.renderImageWithDPI(i, 150);
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                ImageIO.write(image, "png", baos);
                byte[] imageBytes = baos.toByteArray();

                String pageText = visionModelService.describeImage(
                        imageBytes,
                        org.springframework.util.MimeType.valueOf("image/png"),
                        "请仔细识别并提取这张图片中的所有文字内容，保持原始格式和结构。这是PDF的第" + (i + 1) + "页（共" + pageCount + "页）。如果有表格，请用文本形式还原。"
                );

                if (StringUtils.hasText(pageText)) {
                    Map<String, Object> metadata = sourceMetadata(pdfFile);
                    metadata.put("pageNumber", i + 1);
                    metadata.put("sourceType", "scanned-pdf");
                    allDocs.add(new Document(pageText, metadata));
                }
            }

            OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(400, 100);
            return splitter.apply(allDocs);
        } finally {
            pdDocument.close();
        }
    }

    /**
     * 重新入库：对已落盘的文件重新执行分片、嵌入和 ES 写入。
     * 用于入库失败后的补录，或更新已有文件的内容。
     *
     * @param filename 上传目录中的文件名
     */
    public UploadedDocumentResult reingest(String filename) throws Exception {
        Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        // 只取文件名部分，防止路径遍历
        String safeName = Paths.get(filename).getFileName().toString();
        Path filePath = uploadPath.resolve(safeName).normalize();
        if (!filePath.startsWith(uploadPath)) {
            throw new IllegalArgumentException("非法文件路径: " + filename);
        }
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("文件不存在: " + safeName);
        }

        // 1. 先记录旧数据 ID（在写入新数据之前查，确保查到的是旧的）
        List<String> oldVectorIds = Collections.emptyList();
        List<String> oldEsIds = Collections.emptyList();
        try {
            oldVectorIds = embeddingService.findIdsByFilename(safeName);
        } catch (Exception e) {
            log.warn("查询向量库旧数据失败: {}", e.getMessage());
        }
        try {
            oldEsIds = proRagElasticSearchService.findIdsByFilename(safeName);
        } catch (Exception e) {
            log.warn("查询 ES 旧数据失败: {}", e.getMessage());
        }

        // 2. 分片并写入新数据（新 UUID，不会和旧数据冲突）
        List<Document> chunks = splitDocuments(filePath.toFile());
        if (CollectionUtils.isEmpty(chunks)) {
            throw new IllegalArgumentException("文件解析后没有可入库内容: " + safeName);
        }

        List<EsDocumentChunk> esDocs = chunks.stream().map(doc -> {
            EsDocumentChunk es = new EsDocumentChunk();
            es.setId(doc.getId());
            es.setContent(doc.getText());
            es.setMetadata(doc.getMetadata());
            return es;
        }).toList();

        embeddingService.embedAndStore(chunks);
        proRagElasticSearchService.bulkIndex(esDocs);

        // 3. 新数据写入成功后，按旧 ID 清理旧数据（不会误删新数据）
        try {
            embeddingService.deleteByIds(oldVectorIds);
        } catch (Exception e) {
            log.warn("清理向量库旧数据失败，可手动重试: {}", e.getMessage());
        }
        try {
            proRagElasticSearchService.deleteByIds(oldEsIds);
        } catch (Exception e) {
            log.warn("清理 ES 旧数据失败，可手动重试: {}", e.getMessage());
        }

        return new UploadedDocumentResult(
                safeName,
                filePath.getFileName().toString(),
                filePath.toAbsolutePath().toString(),
                chunks.size(),
                "reingested"
        );
    }

    /**
     * 轻量清洗：去除多余空行和首尾空白。
     */
    private List<Document> clean(List<Document> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return documents;
        }
        return documents.stream()
                .map(doc -> {
                    if (doc == null || doc.getText() == null) {
                        return doc;
                    }
                    String text = doc.getText().replaceAll("\n{3,}", "\n\n").trim();
                    return new Document(text, doc.getMetadata());
                })
                .toList();
    }
}
