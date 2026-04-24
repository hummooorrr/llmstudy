package cn.wzw.llm.study.llmstudy.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 问题重写服务：支持智能查询路由、问题分解、富化、多样化和回退策略 */
@Service
@Slf4j
public class QuestionRewriteService {

    public enum QueryStrategy { DIRECT, DECOMPOSE, HYDE }

    public record QueryRouteResult(QueryStrategy strategy, List<String> subQueries, String hypotheticalAnswer) {}

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    //分解提示词
    private static final String DECOMPOSE_PROMPT =
            "# 角色\n"
                    + "你是一名专业的查询逻辑分析专家。\n\n"
                    + "# 任务\n"
                    + "将给定的“用户原始问题”分解为一系列相互独立、逻辑清晰，且可单独用于检索的子查询列表。\n"
                    + "你的输出必须是一个标准的JSON数组格式。\n\n"
                    + "# 用户原始问题\n"
                    + "{QUESTION}\n\n"
                    + "# 输出格式要求 (JSON Array)\n"
                    + "[\n"
                    + "  \"子查询1\",\n"
                    + "  \"子查询2\",\n"
                    + "  \"子查询3\",\n"
                    + "  \"...\"\n"
                    + "]\n\n"
                    + "不强制要求数组元素个数，可根据真实情况输出，至少保留1个。\n\n"
                    + "# 输出\n"
                    + "请直接输出JSON数组，不要包含解释或多余的文字。";

    //问题的富化
    private static final String ENRICH_PROMPT =
            "# 角色\n"
                    + "你是一个专业的问题重写优化器。\n\n"
                    + "# 任务\n"
                    + "根据提供的对话历史和用户原始问题，重写为一个独立、完整、且包含所有必要背景信息的新查询，用于RAG检索。\n\n"
                    + "## 对话历史：\n"
                    + "{CHAT_HISTORY}\n\n"
                    + "## 原始问题：\n"
                    + "{QUESTION}\n\n"
                    + "# 输出\n"
                    + "输出富化过后的新问题，不要包含多余的解释性内容";

    //问题的多样化
    private static final String DIVERSIFY_PROMPT =
            "# 角色\n"
                    + "你是一名专业的语义扩展专家。\n\n"
                    + "# 任务\n"
                    + "为给定的原始问题生成3个语义相同但措辞完全不同、且利于检索的查询变体，以提高检索的召回率。\n"
                    + "你的输出必须是一个标准的JSON数组格式。\n\n"
                    + "# 原始问题\n"
                    + "{QUESTION}\n\n"
                    + "# 输出格式要求 (JSON Array)\n"
                    + "[\n"
                    + "  \"变体1\",\n"
                    + "  \"变体2\",\n"
                    + "  \"变体3\"\n"
                    + "]\n\n"
                    + "# 输出\n"
                    + "请直接输出JSON数组，不要包含解释或多余的文字。";

    private static final String STEP_BACK =
            "# 角色\n"
                    + "你是一个擅长抽象思维和原理推理的专家。\n\n"
                    + "# 任务\n"
                    + "请根据用户提出的具体问题，先后退一步，将其转化为一个更通用、更本质的问题，聚焦于背后的原理、规律、概念或一般性知识，而不是具体细节。\n\n"
                    + "# 原始问题\n"
                    + "{QUESTION}\n\n"
                    + "# 输出\n"
                    + "请只输出改写后的后退问题，不要解释，不要包含原始问题，也不要回答它。";

    private static final String QUESTION = "QUESTION";
    private static final String CHAT_HISTORY = "CHAT_HISTORY";

    // 智能路由 prompt：单次 LLM 调用同时完成分类 + 生成
    private static final String ROUTE_PROMPT =
            "# 角色\n"
                    + "你是一名查询策略分析专家。\n\n"
                    + "# 任务\n"
                    + "分析用户的查询，判断其类型并输出相应的处理结果。\n\n"
                    + "## 策略类型\n"
                    + "- DIRECT: 简单、明确的问题，无需拆分或改写\n"
                    + "- DECOMPOSE: 复杂的、包含多个子问题的查询，需要拆分为独立的子查询\n"
                    + "- HYDE: 概念性/描述性问题，用户的用词可能和文档用词不一致\n\n"
                    + "## 判断标准\n"
                    + "- 如果问题简短明确，只需要一个直接回答 → DIRECT\n"
                    + "- 如果问题包含\"并且\"\"同时\"\"以及\"等连接词，明显包含多个独立子问题 → DECOMPOSE\n"
                    + "- 如果问题是概念性的、描述性的，用户可能用非专业术语提问 → HYDE\n"
                    + "- 默认策略为 DIRECT\n\n"
                    + "# 用户查询\n"
                    + "{QUESTION}\n\n"
                    + "# 输出格式 (JSON)\n"
                    + "对于 DIRECT:\n"
                    + "\\{\"strategy\":\"DIRECT\",\"payload\":null\\}\n\n"
                    + "对于 DECOMPOSE:\n"
                    + "\\{\"strategy\":\"DECOMPOSE\",\"payload\":[\"子查询1\",\"子查询2\"]\\}\n\n"
                    + "对于 HYDE:\n"
                    + "\\{\"strategy\":\"HYDE\",\"payload\":\"这是一个假设性的专业回答，用于语义检索匹配...\"\\}\n\n"
                    + "## HYDE payload 说明\n"
                    + "当策略为 HYDE 时，payload 应该是你对该问题给出的一个假设性专业回答（约100-200字），\n"
                    + "使用文档中可能出现的专业术语和表达方式，以便在语义层面匹配到相关文档。\n\n"
                    + "# 输出\n"
                    + "请只输出JSON，不要包含解释。";

    /**
     * 智能查询路由：单次 LLM 调用完成分类 + 生成
     */
    public QueryRouteResult routeQuery(String query) {
        log.info("===========进入智能查询路由流程===========");
        log.info("原始问题: {}", query);

        PromptTemplate promptTemplate = new PromptTemplate(ROUTE_PROMPT);
        promptTemplate.add(QUESTION, query);

        String result = chatModel.call(promptTemplate.create()).getResult().getOutput().getText();
        log.info("===========路由原始结果: {} ===========", result);

        String cleaned = result.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "");
        }

        JSONObject json = JSON.parseObject(cleaned.trim());
        String strategyStr = json.getString("strategy");

        QueryStrategy strategy;
        try {
            strategy = QueryStrategy.valueOf(strategyStr);
        } catch (Exception e) {
            log.warn("无法识别策略: {}，回退为 DIRECT", strategyStr);
            return new QueryRouteResult(QueryStrategy.DIRECT, List.of(query), null);
        }

        return switch (strategy) {
            case DIRECT -> new QueryRouteResult(strategy, List.of(query), null);
            case DECOMPOSE -> {
                List<String> subQueries = json.getList("payload", String.class);
                yield new QueryRouteResult(strategy, subQueries, null);
            }
            case HYDE -> {
                String hydeAnswer = json.getString("payload");
                yield new QueryRouteResult(strategy, null, hydeAnswer);
            }
        };
    }

    /**
     * 问题分解
     *
     * @param question
     * @return
     */
    public List<String> decompose(String question) {
        log.info("===========进入问题分解流程===========");
        log.info("原始问题: {}", question);
        PromptTemplate promptTemplate = new PromptTemplate(DECOMPOSE_PROMPT);
        promptTemplate.add(QUESTION, question);

        String result = chatModel.call(promptTemplate.create()).getResult().getOutput().getText();
        log.info("===========问题分解完成，结果: {} ===========", result);
        return JSON.parseArray(result, String.class);
    }

    /**
     * 问题富化
     */
    public String enrich(String chatHistory, String question) {
        log.info("===========进入问题富化流程===========");
        log.info("对话历史: {}", chatHistory);
        log.info("原始问题: {}", question);
        PromptTemplate promptTemplate = new PromptTemplate(ENRICH_PROMPT);
        promptTemplate.add(CHAT_HISTORY, chatHistory);
        promptTemplate.add(QUESTION, question);

        String result = chatModel.call(promptTemplate.create()).getResult().getOutput().getText();
        log.info("===========问题富化完成，结果: {} ===========", result);
        return result;
    }

    /**
     * 问题多样化
     */
    public List<String> diversify(String question) {
        log.info("===========进入问题多样化流程===========");
        log.info("原始问题: {}", question);
        PromptTemplate promptTemplate = new PromptTemplate(DIVERSIFY_PROMPT);
        promptTemplate.add(QUESTION, question);

        String result = chatModel.call(promptTemplate.create()).getResult().getOutput().getText();
        log.info("===========问题多样化完成，结果: {} ===========", result);
        return JSON.parseArray(result, String.class);
    }

    /**
     * 问题回退
     *
     * @param question
     * @return
     */
    public String stepBack(String question) {
        log.info("===========进入问题回退流程===========");
        log.info("原始问题: {}", question);
        PromptTemplate promptTemplate = new PromptTemplate(STEP_BACK);
        promptTemplate.add(QUESTION, question);

        String result = chatModel.call(promptTemplate.create()).getResult().getOutput().getText();
        log.info("===========问题回退完成，结果: {} ===========", result);
        return result;
    }

    // 组合方法
    public List<String> rewriteQuery(String query) {
        log.info("===========进入问题重写组合策略流程===========");
        log.info("原始问题: {}", query);

        //回退
        String stepBackQuery = this.stepBack(query);

        // 分解
        List<String> decomposedQueries = this.decompose(stepBackQuery);

        // 多样化
        List<String> finalQueries = new ArrayList<>();
        for (String subQuery : decomposedQueries) {
            List<String> variations = this.diversify(subQuery);
            finalQueries.addAll(variations);
        }

        if (finalQueries.isEmpty()) {
            finalQueries.add(query);
        }

        log.info("===========组合重写完成，最终查询列表: {} ===========", finalQueries);
        return finalQueries;
    }
}

