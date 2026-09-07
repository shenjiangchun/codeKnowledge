package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.knowledgegraph.service.CrossServiceBuildService;
import com.huawei.hisi.model.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrossServiceBuildControllerTest {

    @Mock
    private CrossServiceBuildService buildService;

    @InjectMocks
    private CrossServiceBuildController controller;

    @Test
    @DisplayName("build returns success with normalized paths on completion")
    void build_returnsSuccess() {
        var request = new CrossServiceBuildController.BuildRequest(List.of("/a", "/b"));

        ApiResponse<Map<String, Object>> response = controller.build(request);

        assertThat(response.getCode()).isEqualTo(200);
        verify(buildService).build(List.of("/a", "/b"));
    }

    @Test
    @DisplayName("build returns 400 when fewer than 2 project paths")
    void build_returns400OnTooFewPaths() {
        var request = new CrossServiceBuildController.BuildRequest(List.of("/a"));

        ApiResponse<Map<String, Object>> response = controller.build(request);

        assertThat(response.getCode()).isEqualTo(400);
        verifyNoInteractions(buildService);
    }

    @Test
    @DisplayName("build returns 400 on IllegalArgumentException from service")
    void build_returns400OnBadArg() {
        var request = new CrossServiceBuildController.BuildRequest(List.of("/a", "/b"));
        doThrow(new IllegalArgumentException("No KG")).when(buildService).build(anyList());

        ApiResponse<Map<String, Object>> response = controller.build(request);

        assertThat(response.getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("build returns 500 on IllegalStateException from service")
    void build_returns500OnError() {
        var request = new CrossServiceBuildController.BuildRequest(List.of("/a", "/b"));
        doThrow(new IllegalStateException("boom")).when(buildService).build(anyList());

        ApiResponse<Map<String, Object>> response = controller.build(request);

        assertThat(response.getCode()).isEqualTo(500);
    }
}
