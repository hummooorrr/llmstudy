package cn.wzw.llm.study.llmstudy;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Service
public class DocumentReaderStrategySelector {

    private final List<DocumentReaderStrategy> strategies;

    public DocumentReaderStrategySelector(List<DocumentReaderStrategy> strategies) {
        this.strategies = strategies;
    }

    public List<Document> read(File file) throws IOException {
        for (DocumentReaderStrategy strategy : strategies) {
            if (strategy.supports(file)) {
                List<Document> documents = strategy.read(file);
                enrichWithSourceMetadata(documents, file);
                return documents;
            }
        }
        throw new IllegalArgumentException("不支持的文件类型: " + file.getName());
    }

    /**
     * 为所有Reader产出的Document统一注入来源文件元数据
     * 无论哪个Reader，入库后都能通过filename/filePath追溯到源文件
     */
    private void enrichWithSourceMetadata(List<Document> documents, File file) {
        for (Document doc : documents) {
            doc.getMetadata().putIfAbsent("filename", file.getName());
            doc.getMetadata().putIfAbsent("filePath", file.getAbsolutePath());
        }
    }
}
