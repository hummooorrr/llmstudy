package cn.wzw.llm.study.llmstudy.service;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 订单管理业务逻辑服务：提供订单查询与退款操作
 */
@Service
public class OrderManageService {

    public String getOrderById(String orderId) {
        return "订单号：" + orderId;
    }

    public String refund(String orderId, String reason) {
        System.out.println("退款成功");
        return UUID.randomUUID().toString();
    }
}