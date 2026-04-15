package cn.wzw.llm.study.llmstudy;


import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@RestController
@RequestMapping("/FunctionCallReturnOrderController")
public class FunctionCallReturnOrderController implements InitializingBean {

    private ChatClient chatClient;

    @Autowired
    private ChatModel zhiPuAiChatModel;

    @Value("classpath:prompts/returnorder.st")
    private Resource returnorderPrompt;

    @Autowired
    private OrderTools orderTools;

    @GetMapping("/newChat")
    public OrderChat newChat(String userId, String orderId, HttpServletResponse httpServletResponse) {
        httpServletResponse.setCharacterEncoding("UTF-8");

        //模拟数据库创建一个chat的记录，获取到他的唯一id。
        String chatId = UUID.randomUUID().toString();

        return chatClient
                .prompt()
                .tools(orderTools)
                .user(String.format("我要咨询订单相关的售后问题，我的用户id是%s,我的订单号是: %s ,本地的对话Id是 %s，当前状态是 %s", userId, orderId, chatId, ChatStatus.CHAT_START.name()))
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId)
                        .param("chat_memory_retrieve_size", 100))
                .call().entity(OrderChat.class);
    }

    @GetMapping("/ask")
    public Flux<String> ask(String question, String chatId, HttpServletResponse httpServletResponse) {
        httpServletResponse.setCharacterEncoding("UTF-8");

        return chatClient
                .prompt()
                .user(question).tools(orderTools)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId)
                        .param("chat_memory_retrieve_size", 100))
                .stream().content();
    }


    @Override
    public void afterPropertiesSet() throws Exception {
        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(100).build();

        chatClient = ChatClient.builder(zhiPuAiChatModel)
                // 实现 Logger 的 Advisor
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                ).defaultSystem(returnorderPrompt) // 设置默认系统提示词
                // 设置 ChatClient 中 ChatModel 的 Options 参数
                .defaultOptions(
                        ZhiPuAiChatOptions.builder()
                                .temperature(0.7)
                                .build()
                )
                .build();
    }

    public record OrderChat(@JsonPropertyDescription("订单号") String orderId
            , @JsonPropertyDescription("用户Id") String userId
            , @JsonPropertyDescription("对话Id") String chatId
            , @JsonPropertyDescription("对话状态") ChatStatus status) {

    }
}
