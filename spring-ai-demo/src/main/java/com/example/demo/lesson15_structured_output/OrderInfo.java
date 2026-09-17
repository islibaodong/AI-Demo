package com.example.demo.lesson15_structured_output;

/**
 * 第 15 课的结构化输出目标对象。
 *
 * <p>纯 record（不可变数据载体），Jackson 3 直接支持 record 反序列化——
 * 与 lesson02 的 TranslationResult 同款用法。</p>
 */
public record OrderInfo(String orderId, String status, int amount) {
}
