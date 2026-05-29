package com.huawei.hisi.knowledgegraph.link;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Links services via gRPC/Dubbo RPC calls by scanning {@code .proto} files
 * and matching service/method definitions to outbound RPC call sites.
 *
 * <p>Current implementation: placeholder. Full proto scanning and gRPC
 * call-site matching will be added in a future phase.
 */
@Component
@Order(4)
@RequiredArgsConstructor
@Slf4j
public class GrpcLinkStrategy implements LinkStrategy {

    @Override
    public void link(List<String> projectPaths) {
        log.info("[GrpcLink] gRPC/Dubbo proto scanning for projectPaths: {} — not yet active", projectPaths);
    }
}
