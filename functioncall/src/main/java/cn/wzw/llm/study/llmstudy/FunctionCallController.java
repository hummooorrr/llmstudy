package cn.wzw.llm.study.llmstudy;


import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/FunctionCallController")
public class FunctionCallController implements InitializingBean {

    private ChatClient chatClient;

    @Autowired
    private ChatModel zhiPuAiChatModel;

    @GetMapping("/functionCall1")
    public Flux<String> functionCall1(HttpServletResponse response, String city) {
        response.setCharacterEncoding("UTF-8");
        return chatClient
                .prompt().tools(new TimeTools())
                .user(city + "现在几点了？")
                .stream().content();
    }

    @GetMapping("/functionCall2")
    public Flux<String> functionCall2(HttpServletResponse response, String city) {
        response.setCharacterEncoding("UTF-8");
        return chatClient
                .prompt()
                .tools("getTimeFunction")  // 引用 Bean 名称
                .user(city + "现在几点了？")
                .stream().content();
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
}
