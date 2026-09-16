package com.example.demo.lesson02_prompt;

/**
 * 第 2 课：结构化输出的目标类型。
 *
 * <p>用普通 class（含默认构造器 + getter/setter）最保险：
 * Spring AI 的 BeanOutputConverter 借助 Jackson 把模型吐的 JSON 反序列化成这个对象。
 * 如果你用的 Jackson 版本支持 record，也能改用 record，但这里采用最通用的写法。</p>
 *
 * <p>注意：为了让模型稳定输出 JSON，系统提示词会明确要求 "只输出 JSON 且包含这些字段"，
 * 然后我们再用 BeanOutputConverter 去解析并校验。</p>
 */
public class TranslationResult {

    /** 原文 */
    private String en;
    /** 译文 */
    private String zh;
    /** 一句话补充说明，例如翻译时保留的口吻 */
    private String note;

    // Jackson 反序列化需要一个无参构造器
    public TranslationResult() {
    }

    public String getEn() {
        return en;
    }

    public void setEn(String en) {
        this.en = en;
    }

    public String getZh() {
        return zh;
    }

    public void setZh(String zh) {
        this.zh = zh;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}