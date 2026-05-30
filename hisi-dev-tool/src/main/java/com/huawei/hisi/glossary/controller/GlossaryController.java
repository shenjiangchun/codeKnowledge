package com.huawei.hisi.glossary.controller;

import com.huawei.hisi.glossary.model.GlossaryTerm;
import com.huawei.hisi.glossary.repository.GlossaryTermRepository;
import com.huawei.hisi.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/glossary")
@RequiredArgsConstructor
@Slf4j
public class GlossaryController {

    private final GlossaryTermRepository glossaryTermRepository;

    @GetMapping
    public ApiResponse<List<GlossaryTerm>> list(@RequestParam String projectPath) {
        try {
            List<GlossaryTerm> terms = glossaryTermRepository.findByProjectPath(projectPath);
            return ApiResponse.success(terms);
        } catch (Exception e) {
            log.error("[Glossary] Failed to list terms for project {}", projectPath, e);
            return ApiResponse.error("Failed to list glossary terms");
        }
    }

    @PostMapping
    public ApiResponse<GlossaryTerm> create(@RequestBody GlossaryTerm term) {
        try {
            validateTerm(term);
            GlossaryTerm saved = glossaryTermRepository.insert(term);
            log.info("[Glossary] Created term: '{}' → '{}' for project {}",
                    saved.getWrongTerm(), saved.getCorrectTerm(), saved.getProjectPath());
            return ApiResponse.success(saved);
        } catch (IllegalArgumentException e) {
            log.warn("[Glossary] Create rejected: {}", e.getMessage());
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("[Glossary] Failed to create term", e);
            return ApiResponse.error("Failed to create glossary term");
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<GlossaryTerm> update(@PathVariable Long id, @RequestBody GlossaryTerm term) {
        try {
            glossaryTermRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Glossary term not found: id=" + id));
            term.setId(id);
            validateTerm(term);
            glossaryTermRepository.update(term);
            GlossaryTerm updated = glossaryTermRepository.findById(id).orElse(term);
            log.info("[Glossary] Updated term id={}", id);
            return ApiResponse.success(updated);
        } catch (IllegalArgumentException e) {
            log.warn("[Glossary] Update rejected: {}", e.getMessage());
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("[Glossary] Failed to update term {}", id, e);
            return ApiResponse.error("Failed to update glossary term");
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            int rows = glossaryTermRepository.deleteById(id);
            if (rows == 0) {
                return ApiResponse.error(404, "Glossary term not found: id=" + id);
            }
            log.info("[Glossary] Deleted term id={}", id);
            return ApiResponse.success(Map.of("id", id, "deleted", true));
        } catch (Exception e) {
            log.error("[Glossary] Failed to delete term {}", id, e);
            return ApiResponse.error("Failed to delete glossary term");
        }
    }

    private void validateTerm(GlossaryTerm term) {
        if (term.getProjectPath() == null || term.getProjectPath().isBlank()) {
            throw new IllegalArgumentException("Project path is required");
        }
        if (term.getWrongTerm() == null || term.getWrongTerm().isBlank()) {
            throw new IllegalArgumentException("Wrong term is required");
        }
        if (term.getCorrectTerm() == null || term.getCorrectTerm().isBlank()) {
            throw new IllegalArgumentException("Correct term is required");
        }
    }
}
