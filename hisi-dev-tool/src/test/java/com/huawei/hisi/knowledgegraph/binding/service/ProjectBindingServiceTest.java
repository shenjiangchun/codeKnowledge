package com.huawei.hisi.knowledgegraph.binding.service;

import com.huawei.hisi.knowledgegraph.binding.model.ProjectBinding;
import com.huawei.hisi.knowledgegraph.binding.repository.ProjectBindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ProjectBindingService 单元测试：绑定 CRUD + resolve 范围展开 + 唯一约束冲突透传。
 */
@ExtendWith(MockitoExtension.class)
class ProjectBindingServiceTest {

    @Mock
    private ProjectBindingRepository repository;

    private ProjectBindingService service;

    @BeforeEach
    void setUp() {
        service = new ProjectBindingService(repository);
    }

    // ==================== create Tests ====================

    @Test
    @DisplayName("创建绑定 - 路径规范化后写入")
    void create_normalizesPaths() {
        when(repository.findByBackendPath(anyString())).thenReturn(List.of());
        when(repository.insert(any(ProjectBinding.class))).thenAnswer(inv -> {
            ProjectBinding b = inv.getArgument(0);
            b.setId(1L);
            return b;
        });

        ProjectBinding saved = service.create("C:\\proj\\backend\\", "C:\\proj\\frontend\\");

        assertEquals("C:/proj/backend", saved.getBackendPath());
        assertEquals("C:/proj/frontend", saved.getFrontendPath());
        assertEquals(1L, saved.getId());
        verify(repository, times(1)).insert(any(ProjectBinding.class));
    }

    @Test
    @DisplayName("创建绑定 - 空路径拒绝")
    void create_blankPath_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.create("", "/frontend"));
        assertThrows(IllegalArgumentException.class, () -> service.create("/backend", "  "));
        verify(repository, never()).insert(any());
    }

    @Test
    @DisplayName("创建绑定 - 已存在（显式查重命中）抛 DuplicateKeyException")
    void create_alreadyExists_throwsDuplicateKey() {
        ProjectBinding existing = ProjectBinding.builder()
            .id(1L).backendPath("/backend").frontendPath("/frontend").build();
        when(repository.findByBackendPath("/backend")).thenReturn(List.of(existing));

        assertThrows(DuplicateKeyException.class, () -> service.create("/backend", "/frontend"));
        verify(repository, never()).insert(any());
    }

    @Test
    @DisplayName("创建绑定 - 唯一约束冲突透传（不吞异常）")
    void create_duplicateKey_propagates() {
        when(repository.findByBackendPath(anyString())).thenReturn(List.of());
        when(repository.insert(any(ProjectBinding.class)))
            .thenThrow(new DuplicateKeyException("duplicate") {});

        assertThrows(DuplicateKeyException.class, () -> service.create("/backend", "/frontend"));
    }

    // ==================== list / delete Tests ====================

    @Test
    @DisplayName("查询绑定列表")
    void list_returnsAll() {
        ProjectBinding b = ProjectBinding.builder().id(1L).backendPath("/b").frontendPath("/f").build();
        when(repository.findAll()).thenReturn(List.of(b));

        List<ProjectBinding> result = service.list();

        assertEquals(1, result.size());
        assertEquals("/b", result.get(0).getBackendPath());
    }

    @Test
    @DisplayName("删除绑定 - 存在则返回 true")
    void delete_returnsTrueWhenRowsAffected() {
        when(repository.deleteById(1L)).thenReturn(1);
        assertTrue(service.delete(1L));
    }

    @Test
    @DisplayName("删除绑定 - 不存在则返回 false")
    void delete_returnsFalseWhenNoRows() {
        when(repository.deleteById(99L)).thenReturn(0);
        assertFalse(service.delete(99L));
    }

    // ==================== resolve Tests ====================

    @Test
    @DisplayName("resolve - 选后端目录命中前端")
    void resolve_backendHitsFrontend() {
        ProjectBinding b = ProjectBinding.builder().id(1L).backendPath("/backend").frontendPath("/frontend").build();
        when(repository.findAll()).thenReturn(List.of(b));

        List<String> result = service.resolve("/backend");

        assertEquals(List.of("/backend", "/frontend"), result);
    }

    @Test
    @DisplayName("resolve - 选前端目录命中后端")
    void resolve_frontendHitsBackend() {
        ProjectBinding b = ProjectBinding.builder().id(1L).backendPath("/backend").frontendPath("/frontend").build();
        when(repository.findAll()).thenReturn(List.of(b));

        List<String> result = service.resolve("/frontend");

        assertEquals(List.of("/frontend", "/backend"), result);
    }

    @Test
    @DisplayName("resolve - 无绑定退化单项目")
    void resolve_noBinding_singleProject() {
        when(repository.findAll()).thenReturn(List.of());

        List<String> result = service.resolve("/backend");

        assertEquals(List.of("/backend"), result);
    }

    @Test
    @DisplayName("resolve - 路径规范化后匹配")
    void resolve_normalizesBeforeMatch() {
        ProjectBinding b = ProjectBinding.builder().id(1L).backendPath("C:/proj/backend").frontendPath("C:/proj/frontend").build();
        when(repository.findAll()).thenReturn(List.of(b));

        List<String> result = service.resolve("C:\\proj\\backend\\");

        assertEquals(List.of("C:/proj/backend", "C:/proj/frontend"), result);
    }
}
