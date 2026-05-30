package com.huawei.hisi.glossary.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlossaryTerm {

    private Long id;
    private String projectPath;
    private String wrongTerm;
    private String correctTerm;
    private String context;
    private Long createdAt;
    private Long updatedAt;
}
