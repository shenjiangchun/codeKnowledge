package com.huawei.hisi.knowledgegraph.util;

import com.huawei.hisi.utils.PathUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class ProjectPathResolver {

    private ProjectPathResolver() {}

    /**
     * Merge single projectPath + optional projectPaths list into a deduplicated, normalized list.
     * Priority: projectPaths list > single projectPath > empty.
     */
    public static List<String> resolve(String projectPath, List<String> projectPaths) {
        LinkedHashSet<String> result = new LinkedHashSet<>();

        if (projectPaths != null && !projectPaths.isEmpty()) {
            for (String p : projectPaths) {
                if (p != null && !p.isBlank()) {
                    result.add(normalize(p));
                }
            }
        }

        if (result.isEmpty() && projectPath != null && !projectPath.isBlank()) {
            result.add(normalize(projectPath));
        }

        return new ArrayList<>(result);
    }

    /**
     * 统一委托 PathUtils.normalize —— 反斜杠→正斜杠、trim、去末尾斜杠。
     */
    public static String normalize(String path) {
        return PathUtils.normalize(path);
    }
}
