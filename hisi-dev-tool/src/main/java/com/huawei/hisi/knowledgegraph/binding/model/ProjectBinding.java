package com.huawei.hisi.knowledgegraph.binding.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 前后端目录绑定关系（SQLite 本地元数据）。
 *
 * <p>一条绑定记录 = 「后端目录 ↔ 前端目录」二元关系，作为「构建前后端完整图谱」
 * 与「检索/总览范围展开」的基础。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectBinding {

    private Long id;
    private String backendPath;
    private String frontendPath;
    private Long createdAt;
    private Long updatedAt;
}
