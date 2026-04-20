package cn.wzw.llm.study.llmstudy;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词工程替换变量
 */
@RestController
@RequestMapping("/client")
public class ChatClientController implements InitializingBean {

    @Autowired
    private ChatModel zhiPuAiChatModel;

    private ChatClient chatClient;

    @GetMapping("/simpleCall")
    public String simpleCall(String message) {
        return chatClient.prompt(message).call().content();
    }

    @GetMapping("/stream")
    public Flux<String> stream(String message) {
        return chatClient.prompt(message).stream().content();
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

    @GetMapping("/promptsEngineer6")
    public Flux<String> chat6(@RequestParam(value = "message") String message, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");

        PromptTemplate promptTemplate = new PromptTemplate("请给我推荐几个关于{topic}的开源项目");
        promptTemplate.add("topic", message);// 可能会被promptTemplate.create中传入的map覆盖

        return chatClient.prompt(promptTemplate.create()).system("你是一个专业的的github项目收集人员").stream().content();
    }

    @GetMapping("/promptsEngineer7")
    public Flux<String> chat7(@RequestParam(value = "message") String message, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");

        HashMap variables = new HashMap();
        variables.put("language", "Java");
        variables.put("topic", message); // 要么只用variables，要么只用promptTemplate.create(map)
        PromptTemplate promptTemplate = PromptTemplate.builder().template("请给我推荐几个关于{topic}的开源项目,要求是和编程语言{language}相关的。").variables(variables).build();

        return chatClient.prompt(promptTemplate.create()).system("你是一个专业的的github项目收集人员").stream().content();
    }

    @GetMapping("/promptsEngineer8")
    public Flux<String> chat8(@RequestParam(value = "message") String message, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");


        Map<String, Object> params = Map.of("composer", message);
        PromptTemplate promptTemplate = PromptTemplate.builder()
                .renderer(StTemplateRenderer.builder()
                        .startDelimiterToken('<')
                        .endDelimiterToken('>')
                        .build())
                .template("""
            告诉我 5 部由 <composer> 作曲的电影名称。
            """)
                .build();

        String prompt = promptTemplate.render(params);
        System.out.println(prompt);
// 输出：告诉我 5 部由 John Williams 作曲的电影名称。
        return chatClient.prompt(promptTemplate.create()).system("你是一个专业的的github项目收集人员").stream().content();
    }


    @Value("classpath:prompts/open-source-system-prompt.st")
    private Resource systemText;

    @GetMapping("/chat2")
    public Flux<String> chat2(@RequestParam(value = "message") String message, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");

        HashMap variables = new HashMap();
        variables.put("language", "Java");
        variables.put("topic", message);
        PromptTemplate promptTemplate = PromptTemplate.builder().resource(systemText).variables(variables).build();

        return chatClient.prompt(promptTemplate.create(Map.of("topic", message))).system("你是一个专业的的github项目收集人员").stream().content();
    }


    @GetMapping("/call1")
    public String call1(String message) {

        List<Message> messages = new ArrayList<>();

        //第一轮对话
        messages.add(new SystemMessage("你是一个旅行推荐师"));

        messages.add(new UserMessage("我想去新疆玩"));
        messages.add(new AssistantMessage("好的，我知道了，你要去新疆，请问你准备什么时候去"));
        messages.add(new UserMessage("我准备元旦的时候去玩"));
        messages.add(new AssistantMessage("好的，请问你想玩那些内容？"));

        messages.add(new UserMessage("我喜欢自然风光"));

        Prompt prompt = new Prompt(messages);
        return zhiPuAiChatModel.call(prompt).getResult().getOutput().getText();
    }


}