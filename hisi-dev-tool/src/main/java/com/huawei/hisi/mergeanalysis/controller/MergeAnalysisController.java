package com.huawei.hisi.mergeanalysis.controller;

import com.huawei.hisi.mergeanalysis.model.DiffResult;
import com.huawei.hisi.mergeanalysis.service.DiffExtractService;
import com.huawei.hisi.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/merge-analysis")
@RequiredArgsConstructor
@Slf4j
public class MergeAnalysisController {

    private final DiffExtractService diffExtractService;

    public record DiffRequest(String projectPath, String sourceBranch, String targetBranch) {}

    @GetMapping("/branches")
    public ApiResponse<List<String>> listBranches(@RequestParam String projectPath) {
        List<String> branches = diffExtractService.listBranches(projectPath);
        return ApiResponse.success(branches);
    }

    @PostMapping("/diff")
    public ApiResponse<DiffResult> getDiff(@RequestBody DiffRequest request) {
        DiffResult result = diffExtractService.extractDiff(
                request.projectPath(), request.sourceBranch(), request.targetBranch());
        return ApiResponse.success(result);
    }
}
