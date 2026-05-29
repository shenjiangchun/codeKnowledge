package com.huawei.hisi.service;

import com.huawei.hisi.model.PromptTemplate;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词服务接口
 */
public interface PromptService {

    /**
     * 获取所有模板
     * @return 模板列表
     */
    List<PromptTemplate> getAllTemplates();

    /**
     * 获取模板
     * @param key 模板键
     * @return 模板对象
     */
    PromptTemplate getTemplate(String key);

    /**
     * 更新模板
     * @param key 模板键
     * @param content 新内容
     * @param variables 变量列表
     */
    void updateTemplate(String key, String content, String variables);

    /**
     * 渲染模板（替换变量）
     * @param key 模板键
     * @param variables 变量映射
     * @return 渲染后的内容
     */
    String render(String key, Map<String, String> variables);

    /**
     * 从内容中提取变量名
     * @param content 内容
     * @return 变量名列表
     */
    List<String> extractVariables(String content);

    /**
     * 渲染模板内容（替换变量）
     * @param content 模板内容
     * @param variables 变量映射
     * @return 渲染后的内容
     */
    String renderContent(String content, Map<String, String> variables);
}
