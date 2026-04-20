package cn.wzw.llm.study.llmstudy;

import com.alibaba.nacos.shaded.io.grpc.internal.JsonUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 结构化输出练习
 */
@RestController
@RequestMapping("/ai/structure")
public class StructuredOutputController implements InitializingBean {

    private ChatClient chatClient;

    @Autowired
    private ChatModel zhiPuAiChatModel;

    @GetMapping("/chat")
    public Flux<String> chat(HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");

        BeanOutputConverter<Book> beanOutputConverter = new BeanOutputConverter<>(Book.class);

        PromptTemplate promptTemplate = new PromptTemplate("""
            请帮我推荐几本java相关的书
            {format}
            """);

        return chatClient.prompt(promptTemplate.create(Map.of("format", beanOutputConverter.getFormat()))).system("你是一个专业的图书推荐人员").stream().content();
    }

    @GetMapping("/chat1")
    public String chat1(HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        Book book = chatClient.prompt("请帮我推荐一本java相关的书").system("你是一个专业的图书推荐人员").call().entity(Book.class);
        return book.toString();
    }

    @GetMapping("/chat2")
    public List<Book> chat2(HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return chatClient.prompt("请帮我推荐几本java相关的书").system("你是一个专业的图书推荐人员").call().entity(new ParameterizedTypeReference<List<Book>>() {
        });
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        chatClient = ChatClient.builder(zhiPuAiChatModel)
                // 实现 Logger 的 Advisor
                .defaultAdvisors(
                        new SimpleLoggerAdvisor()
                ).defaultSystem("请用中文回答问题")
                // 设置 ChatClient 中 ChatModel 的 Options 参数
                .defaultOptions(
                        ZhiPuAiChatOptions.builder()
                                .temperature(0.7)
                                .build()
                )
                .build();
    }

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