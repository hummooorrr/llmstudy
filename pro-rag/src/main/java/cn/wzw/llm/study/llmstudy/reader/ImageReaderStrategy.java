package cn.wzw.llm.study.llmstudy.reader;

import cn.wzw.llm.study.llmstudy.service.VisionModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片文件读取策略：使用视觉模型识别图片中的文字内容
 */
@Component
@Slf4j
public class ImageReaderStrategy implements DocumentReaderStrategy {

    private static final Map<String, MimeType> EXT_TO_MIME = Map.of(
            ".jpg", MimeType.valueOf("image/jpeg"),
            ".jpeg", MimeType.valueOf("image/jpeg"),
            ".png", MimeType.valueOf("image/png"),
            ".bmp", MimeType.valueOf("image/bmp"),
            ".tiff", MimeType.valueOf("image/tiff"),
            ".gif", MimeType.valueOf("image/gif")
    );

    @Autowired
    private VisionModelService visionModelService;

    @Override
    public boolean supports(File file) {
        String name = file.getName().toLowerCase();
        return EXT_TO_MIME.containsKey(extension(name));
    }

    @Override
    public List<Document> read(File file) throws IOException {
        String name = file.getName().toLowerCase();
        String ext = extension(name);
        MimeType mimeType = EXT_TO_MIME.get(ext);

        log.info("使用视觉模型识别图片: {}", file.getName());
        byte[] imageBytes = Files.readAllBytes(file.toPath());
        String text = visionModelService.describeImage(imageBytes, mimeType, null);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("filename", file.getName());
        metadata.put("filePath", file.getAbsolutePath());
        metadata.put("sourceType", "image");

        return List.of(new Document(text, metadata));
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : "";
    }
}
