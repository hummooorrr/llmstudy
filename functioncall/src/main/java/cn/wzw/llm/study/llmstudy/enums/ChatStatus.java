package cn.wzw.llm.study.llmstudy.enums;

/**
 * 售后对话状态枚举：追踪退单聊天机器人当前所处阶段
 */
public enum ChatStatus {
    CHAT_START,           // 对话开始
    PROBLEM_CONFIRMING,   // 问题确认中
    REFUND_PROCESSING,    // 退款处理中
    CHAT_CLOSED           // 对话结束
}
