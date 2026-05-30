package com.huawei.hisi.apm.controller;

import com.huawei.hisi.apm.model.ApmTestCase;
import com.huawei.hisi.apm.repository.ApmTestCaseRepository;
import com.huawei.hisi.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for APM test case CRUD.
 * <p>
 * Test cases persist request configurations (method, URL, headers, params, body)
 * so users can replay them later during APM debugging sessions.
 */
@RestController
@RequestMapping("/api/apm/test-cases")
@RequiredArgsConstructor
@Slf4j
public class ApmTestCaseController {

    private final ApmTestCaseRepository testCaseRepository;

    /**
     * List all test cases for a project.
     * <p>
     * GET /api/apm/test-cases?projectPath=/path/to/project
     */
    @GetMapping
    public ApiResponse<List<ApmTestCase>> list(@RequestParam String projectPath) {
        try {
            List<ApmTestCase> cases = testCaseRepository.findByProjectPath(projectPath);
            return ApiResponse.success(cases);
        } catch (Exception e) {
            log.error("[ApmTestCase] Failed to list test cases for project {}", projectPath, e);
            return ApiResponse.error("Failed to list test cases");
        }
    }

    /**
     * Get a single test case by ID.
     * <p>
     * GET /api/apm/test-cases/{id}
     */
    @GetMapping("/{id}")
    public ApiResponse<ApmTestCase> getById(@PathVariable Long id) {
        try {
            ApmTestCase testCase = testCaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Test case not found: id=" + id));
            return ApiResponse.success(testCase);
        } catch (IllegalArgumentException e) {
            log.warn("[ApmTestCase] Not found: {}", e.getMessage());
            return ApiResponse.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("[ApmTestCase] Failed to get test case {}", id, e);
            return ApiResponse.error("Failed to get test case");
        }
    }

    /**
     * Create a new test case.
     * <p>
     * POST /api/apm/test-cases
     */
    @PostMapping
    public ApiResponse<ApmTestCase> create(@RequestBody ApmTestCase testCase) {
        try {
            validateTestCase(testCase);
            ApmTestCase saved = testCaseRepository.insert(testCase);
            log.info("[ApmTestCase] Created test case '{}' for project {}", saved.getName(), saved.getProjectPath());
            return ApiResponse.success(saved);
        } catch (IllegalArgumentException e) {
            log.warn("[ApmTestCase] Create rejected: {}", e.getMessage());
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("[ApmTestCase] Failed to create test case", e);
            return ApiResponse.error("Failed to create test case");
        }
    }

    /**
     * Update an existing test case.
     * <p>
     * PUT /api/apm/test-cases/{id}
     */
    @PutMapping("/{id}")
    public ApiResponse<ApmTestCase> update(@PathVariable Long id, @RequestBody ApmTestCase testCase) {
        try {
            testCaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Test case not found: id=" + id));
            testCase.setId(id);
            validateTestCase(testCase);
            testCaseRepository.update(testCase);
            ApmTestCase updated = testCaseRepository.findById(id).orElse(testCase);
            log.info("[ApmTestCase] Updated test case id={}", id);
            return ApiResponse.success(updated);
        } catch (IllegalArgumentException e) {
            log.warn("[ApmTestCase] Update rejected: {}", e.getMessage());
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("[ApmTestCase] Failed to update test case {}", id, e);
            return ApiResponse.error("Failed to update test case");
        }
    }

    /**
     * Delete a test case by ID.
     * <p>
     * DELETE /api/apm/test-cases/{id}
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            int rows = testCaseRepository.deleteById(id);
            if (rows == 0) {
                return ApiResponse.error(404, "Test case not found: id=" + id);
            }
            log.info("[ApmTestCase] Deleted test case id={}", id);
            return ApiResponse.success(Map.of("id", id, "deleted", true));
        } catch (Exception e) {
            log.error("[ApmTestCase] Failed to delete test case {}", id, e);
            return ApiResponse.error("Failed to delete test case");
        }
    }

    private void validateTestCase(ApmTestCase testCase) {
        if (testCase.getName() == null || testCase.getName().isBlank()) {
            throw new IllegalArgumentException("Test case name is required");
        }
        if (testCase.getProjectPath() == null || testCase.getProjectPath().isBlank()) {
            throw new IllegalArgumentException("Project path is required");
        }
    }
}
