package com.huawei.hisi.knowledgegraph.binding.service;

import com.huawei.hisi.knowledgegraph.binding.model.ProjectBinding;
import com.huawei.hisi.knowledgegraph.binding.repository.ProjectBindingRepository;
import com.huawei.hisi.utils.PathUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 前后端绑定关系服务。
 *
 * <p>提供绑定 CRUD 与「绑定范围展开」：给定任一（前端或后端）目录，展开为该目录
 * 及其所有绑定对中的对侧目录集合，供检索/总览入口复用。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectBindingService {

    private final ProjectBindingRepository repository;

    /**
     * 创建绑定（先规范化路径，再写入；重复绑定抛冲突）。
     *
     * @return 写入后的绑定记录（含自增 id）
     * @throws IllegalArgumentException 后端/前端路径为空
     * @throws DuplicateKeyException 该 (backendPath, frontendPath) 已存在
     */
    public ProjectBinding create(String backendPath, String frontendPath) {
        String backend = PathUtils.normalize(backendPath);
        String frontend = PathUtils.normalize(frontendPath);
        if (backend.isEmpty() || frontend.isEmpty()) {
            throw new IllegalArgumentException("后端目录与前端目录均不能为空");
        }
        // 显式查重：SQLite 唯一约束冲突的 SQLException 无 SQLState，Spring 不会翻译为
        // DuplicateKeyException（抛 UncategorizedSQLException），故写入前查重以保持契约跨驱动一致。
        boolean exists = repository.findByBackendPath(backend).stream()
                .anyMatch(b -> frontend.equals(b.getFrontendPath()));
        if (exists) {
            throw new DuplicateKeyException("绑定已存在: " + backend + " <-> " + frontend);
        }
        ProjectBinding binding = ProjectBinding.builder()
            .backendPath(backend)
            .frontendPath(frontend)
            .build();
        ProjectBinding saved = repository.insert(binding);
        log.info("[ProjectBinding] 绑定建立: backend={}, frontend={}, id={}", backend, frontend, saved.getId());
        return saved;
    }

    /** 查询全部绑定记录。 */
    public List<ProjectBinding> list() {
        return repository.findAll();
    }

    /** 删除指定绑定；返回是否删除成功。 */
    public boolean delete(Long id) {
        int rows = repository.deleteById(id);
        log.info("[ProjectBinding] 删除绑定 id={}, rows={}", id, rows);
        return rows > 0;
    }

    /**
     * 绑定范围展开：给定一个目录，返回「自身 + 所有包含该路径的绑定对中的对侧目录」。
     *
     * <p>无任何绑定时退化为仅自身（单项目模式，向后兼容）。结果去重、保持稳定顺序。</p>
     *
     * @param projectPath 用户给定的目录（前端或后端均可）
     * @return 展开后的路径集合（含自身，规范化）
     */
    public List<String> resolve(String projectPath) {
        String target = PathUtils.normalize(projectPath);
        Set<String> result = new LinkedHashSet<>();
        if (!target.isEmpty()) {
            result.add(target);
        }

        for (ProjectBinding binding : repository.findAll()) {
            if (target.equals(binding.getBackendPath())) {
                result.add(binding.getFrontendPath());
            } else if (target.equals(binding.getFrontendPath())) {
                result.add(binding.getBackendPath());
            }
        }
        return List.copyOf(result);
    }
}
