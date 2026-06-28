package cn.wzw.llm.study.llmstudy;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Pro-Rag 高级 RAG 学习模块启动类 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ProRagApplication {

    public static void main(String[] args) {
        // 放宽 Apache POI 的 zip-bomb 安全阈值，避免大型 docx（含大量嵌入图片，
        // 内部条目 > 1000）被默认 MAX_FILE_COUNT 误判为恶意文件而拒收。
        // 这些阈值原本只在 TikaReaderStrategy / WordHeaderTextSplitter 里局部设置，
        // 但 DocxIngestionPipeline 走的是另一条链路（XWPFDocument 直读），
        // 没有设置，会用默认 1000 触发 "embeds more internal file entries than expected"。
        // 这里在启动时统一设一次，覆盖所有读取路径。
        ZipSecureFile.setMaxFileCount(50000);
        ZipSecureFile.setMaxEntrySize(1L << 32);  // 单条目上限 4GB（zip32 极限）
        ZipSecureFile.setMinInflateRatio(0.001);  // 允许高压缩比内容

        SpringApplication.run(ProRagApplication.class, args);
    }
}
