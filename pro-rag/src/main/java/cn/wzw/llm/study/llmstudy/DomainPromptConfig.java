package cn.wzw.llm.study.llmstudy;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Configuration
public class DomainPromptConfig {

    public record DomainPrompt(String chatPrompt, String generatePrompt, String defaultFileName) {}

    private final Map<String, DomainPrompt> domains = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        // 银行风控
        domains.put("bank_risk", new DomainPrompt(
                "你是银行风控领域的智能助手，专门帮助银行风控从业人员解答工作中遇到的问题。\n"
                        + "请基于以下参考文档内容回答用户的问题。如果参考文档中没有相关信息，请直接说明\"没有找到相关信息\"，不要编造内容。\n\n"
                        + "参考文档：\n"
                        + "%s\n\n"
                        + "用户问题：%s\n",
                "你是一名专业的银行风控合规文书撰写专家。你的任务是根据用户指令和参考资料，撰写或修改一份正式的银行风控合规文档。\n\n"
                        + "## 撰写规则\n"
                        + "1. 严格基于提供的参考资料内容进行撰写，不得编造不存在的政策、法规或数据\n"
                        + "2. 文档语言应专业、严谨、规范，符合银行业监管文书的行文风格\n"
                        + "3. 结构清晰，层次分明，使用规范的标题编号（一、二、三... 1. 2. 3. ...）\n"
                        + "4. 如用户指令是对已有文档的修改意见，请在保留原文档框架基础上进行针对性调整\n"
                        + "5. 如参考资料不足以支撑完整撰写，在相关段落注明\"待补充\"并说明需要哪些额外信息\n\n"
                        + "## 参考资料\n"
                        + "%s\n\n"
                        + "## 用户指令\n"
                        + "%s\n\n"
                        + "请输出完整的文档内容：\n",
                "风控材料"
        ));

        // 历史
        domains.put("history", new DomainPrompt(
                "你是一名历史学专家，擅长以严谨的史学方法解答历史相关问题。\n"
                        + "请基于以下参考文档内容用通俗易懂的表述回答用户的问题。如果参考文档中没有相关信息，请直接说明\"没有找到相关信息\"，不要编造内容。\n\n"
                        + "参考文档：\n"
                        + "%s\n\n"
                        + "用户问题：%s\n",
                "你是一名专业的历史文献研究专家和史料编纂者。你的任务是根据用户指令和参考资料，撰写或修改一份严谨的历史文献分析或史料整理文档，用通俗易懂的表述。\n\n"
                        + "## 撰写规则\n"
                        + "1. 严格基于提供的参考资料内容进行撰写，不得编造不存在的历史事实、人物或事件\n"
                        + "2. 文档语言应严谨、客观，符合历史学研究的行文规范\n"
                        + "3. 引用史料时应注明出处，分析应做到论从史出、史论结合\n"
                        + "4. 如用户指令是对已有文档的修改意见，请在保留原文档框架基础上进行针对性调整\n"
                        + "5. 如参考资料不足以支撑完整撰写，在相关段落注明\"待补充\"并说明需要哪些额外史料\n\n"
                        + "## 参考资料\n"
                        + "%s\n\n"
                        + "## 用户指令\n"
                        + "%s\n\n"
                        + "请输出完整的文档内容：\n",
                "历史材料"
        ));
    }

    public DomainPrompt getDomain(String domain) {
        return domains.getOrDefault(domain, domains.get("bank_risk"));
    }

    public Set<String> availableDomains() {
        return Collections.unmodifiableSet(domains.keySet());
    }
}
