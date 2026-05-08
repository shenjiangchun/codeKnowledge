package com.huawei.hisi.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.PromptTemplate;
import com.huawei.hisi.service.PromptService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 提示词模板控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptController {

    private final PromptService promptService;

    /**
     * 获取模板列表
     * GET /api/prompts
     */
    @GetMapping
    public ApiResponse<List<PromptTemplate>> getAllTemplates() {
        List<PromptTemplate> templates = promptService.getAllTemplates();
        return ApiResponse.success(templates);
    }

    /**
     * 获取模板详情
     * GET /api/prompts/{key}
     */
    @GetMapping("/{key}")
    public ApiResponse<PromptTemplate> getTemplate(@PathVariable String key) {
        PromptTemplate template = promptService.getTemplate(key);
        if (template == null) {
            return ApiResponse.error("模板不存在");
        }
        return ApiResponse.success(template);
    }

    /**
     * 更新模板
     * PUT /api/prompts/{key}
     */
    @PutMapping("/{key}")
    public ApiResponse<String> updateTemplate(@PathVariable String key, @RequestBody UpdatePromptRequest request) {
        promptService.updateTemplate(key, request.getContent(), request.getVariables());
        log.info("模板更新成功: {}", key);
        return ApiResponse.success("更新成功");
    }

    /**
     * 渲染模板
     * POST /api/prompts/{key}/render
     */
    @PostMapping("/{key}/render")
    public ApiResponse<String> renderTemplate(@PathVariable String key, @RequestBody Map<String, String> variables) {
        String result = promptService.render(key, variables);
        return ApiResponse.success(result);
    }

    /**
     * 提取变量
     * POST /api/prompts/extract-variables
     */
    @PostMapping("/extract-variables")
    public ApiResponse<List<String>> extractVariables(@RequestBody ExtractVariablesRequest request) {
        List<String> variables = promptService.extractVariables(request.getContent());
        return ApiResponse.success(variables);
    }

    /**
     * 更新模板请求
     */
    @Data
    public static class UpdatePromptRequest {
        private String content;
        private String variables;
    }

    /**
     * 提取变量请求
     */
    @Data
    public static class ExtractVariablesRequest {
        private String content;
    }
}
