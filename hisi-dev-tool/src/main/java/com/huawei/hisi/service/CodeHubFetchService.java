package com.huawei.hisi.service;

import com.huawei.hisi.model.GitFetchDto;

import java.io.IOException;
import java.util.*;

public interface CodeHubFetchService {

    /**
     * 批量克隆 Git 仓库
     */
    Map<String, Object> fetchRepositories(List<GitFetchDto> dtos) throws IOException;

}