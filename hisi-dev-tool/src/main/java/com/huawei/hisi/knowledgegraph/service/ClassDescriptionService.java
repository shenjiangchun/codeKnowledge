package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.neo4j.model.ClassNode;
import com.huawei.hisi.neo4j.repository.Neo4jClassNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 类描述生成服务。
 *
 * <p>类描述规则：聚合该类所有方法的描述（有 description 用描述，无则用方法签名）。
 * 类注释（classComment）优先逻辑后续接入（需要图谱构建阶段提取类 Javadoc）。
 *
 * <p>调用时机：在「语义&向量」阶段、方法描述生成完成之后（架构信息抽取之前）。
 */
@Slf4j
@Service
public class ClassDescriptionService {

    private final Neo4jClassNodeRepository classNodeRepository;
    private final Neo4jMethodNodeRepository methodNodeRepository;

    public ClassDescriptionService(Neo4jClassNodeRepository classNodeRepository,
                                   Neo4jMethodNodeRepository methodNodeRepository) {
        this.classNodeRepository = classNodeRepository;
        this.methodNodeRepository = methodNodeRepository;
    }

    /**
     * 为项目所有 ClassNode 生成类描述（聚合方法描述/签名）。
     *
     * @param projectPath 项目路径
     * @return 生成的类描述数量
     */
    public int generateClassDescriptions(String projectPath) {
        List<ClassNode> classes = classNodeRepository.findByProjectPath(projectPath);
        int generated = 0;
        for (ClassNode cls : classes) {
            if (cls.getClassName() == null || cls.getClassName().isBlank()) continue;
            // 取该类所有方法，聚合「有描述用描述，无则方法签名」
            List<String> methodTexts = methodNodeRepository
                    .findByProjectPathAndClassName(projectPath, cls.getClassName())
                    .stream()
                    .map(m -> {
                        String desc = m.getDescription();
                        if (desc != null && !desc.isBlank()) {
                            return desc.trim();
                        }
                        return m.getMethodName() + "(" + (m.getSignature() != null ? m.getSignature() : "") + ")";
                    })
                    .limit(20)  // 限制方法数，避免类描述过长
                    .collect(Collectors.toList());

            if (methodTexts.isEmpty()) continue;
            String description = String.join("；", methodTexts);
            cls.setDescription(description);
            classNodeRepository.save(cls);
            generated++;
        }
        log.info("[ClassDescription] 类描述生成完成: projectPath={}, generated={}/{}",
            projectPath, generated, classes.size());
        return generated;
    }
}
