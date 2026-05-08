package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.knowledgegraph.exception.NoCheckpointException;
import com.huawei.hisi.knowledgegraph.exception.WorkingDirDirtyException;
import com.huawei.hisi.knowledgegraph.service.IncrementalRefreshService;
import com.huawei.hisi.knowledgegraph.service.IncrementalRefreshService.RefreshResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RefreshController.class)
class RefreshControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IncrementalRefreshService refreshService;

    @Test
    @DisplayName("refresh success returns 200 with result")
    void refresh_success_returns200() throws Exception {
        var result = new RefreshResult(false, 5, 2, 3);
        when(refreshService.refresh(eq("/project")))
                .thenReturn(result);

        mockMvc.perform(post("/api/knowledge-graph/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectPath":"/project"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.changedFiles").value(5))
                .andExpect(jsonPath("$.data.deleted").value(2))
                .andExpect(jsonPath("$.data.rebuilt").value(3));
    }

    @Test
    @DisplayName("refresh with no checkpoint returns 409")
    void refresh_noCheckpoint_returns409() throws Exception {
        when(refreshService.refresh(any()))
                .thenThrow(new NoCheckpointException("/project"));

        mockMvc.perform(post("/api/knowledge-graph/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectPath":"/project"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("No checkpoint")));
    }

    @Test
    @DisplayName("refresh with dirty working dir returns 412")
    void refresh_dirtyWorkDir_returns412() throws Exception {
        when(refreshService.refresh(any()))
                .thenThrow(new WorkingDirDirtyException("/project"));

        mockMvc.perform(post("/api/knowledge-graph/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectPath":"/project"}
                                """))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value(412))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("dirty")));
    }

    @Test
    @DisplayName("refresh with missing projectPath returns 400")
    void refresh_missingProjectPath_returns400() throws Exception {
        mockMvc.perform(post("/api/knowledge-graph/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectPath":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("projectPath")));
    }
}
