package cn.wzw.llm.study.llmstudy;

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
        SpringApplication.run(ProRagApplication.class, args);
    }
}
