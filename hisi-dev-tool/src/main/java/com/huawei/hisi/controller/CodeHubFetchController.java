package com.huawei.hisi.controller;

import com.huawei.hisi.model.GitFetchDto;
import com.huawei.hisi.service.CodeHubFetchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/git")
public class CodeHubFetchController {

    @Autowired
    private CodeHubFetchService codeHubFetchService;

    /**
     * 批量克隆 Git 仓库
     * POST /api/git/fetch
     * Body: JSON array of GitFetchDto
     */
    @PostMapping("/fetch")
    public Map<String, Object> fetchRepositories(@RequestBody List<GitFetchDto> gitFetchDtoList) throws IOException {
        return codeHubFetchService.fetchRepositories(gitFetchDtoList);
    }
}