package cn.wzw.llm.study.llmstudy;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;


@AiService
public interface LangChainAiService {


    @UserMessage(fromResource = "open-source-system-prompt.st")
    String chat(String userMessage);


    @SystemMessage("你是一个毒舌博主，擅长怼人")
    @UserMessage("针对用户的内容：{{topic}}，先复述一遍他的问题，然后再回答")
    Flux<String> chatStream(String userMessage);

    @UserMessage("请帮我推荐1本java相关的书")
    @SystemMessage("你是一个专业的图书推荐人员")
    Book getBooks();

    /**
     * @param title       书名
     * @param author      作者
     * @param description 简介
     * @param price       价格
     * @author Hollis
     */
    public record Book(String title, String author, String description, BigDecimal price) {

    }
}