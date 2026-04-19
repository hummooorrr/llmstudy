package cn.wzw.llm.study.llmstudy;

import cn.wzw.llm.study.llmstudy.dto.ingestion.UploadedDocumentResult;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件接收、分片和入库服务。
 * 把上传落盘、切分和双写存储集中到一个地方，便于复用到“上传即生成”等场景。
 */
@Service
public class ProRagDocumentIngestionService {

    @Value("${pro-rag.upload-dir:./pro-rag-files}")
    private String uploadDir;

    @Autowired
    private DocumentReaderStrategySelector documentReaderStrategySelector;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private ProRagElasticSearchService proRagElasticSearchService;

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

        embeddingService.embedAndStore(chunks);

        List<EsDocumentChunk> esDocs = chunks.stream().map(doc -> {
            EsDocumentChunk es = new EsDocumentChunk();
            es.setId(doc.getId());
            es.setContent(doc.getText());
            es.setMetadata(doc.getMetadata());
            return es;
        }).toList();
        proRagElasticSearchService.bulkIndex(esDocs);

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
