package com.huawei.hisi.archdiagnosis.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 垂直领域聚合单测：同领域词 × 分层角色贯穿聚合为领域；
 * 跨包边界不影响聚合；骨架判定（含业务角色 = 完整领域，纯 MODEL/单角色 = 孤立数据对象）。
 */
class VerticalDomainAggregatorTest {

    private final VerticalDomainAggregator aggregator = new VerticalDomainAggregator(null, null);

    private static ClassRoleRecord r(String className, String role) {
        return new ClassRoleRecord(className, role);
    }

    @Test
    @DisplayName("Order 域跨 controller/service/repo/model 四层聚合为一个领域")
    void aggregatesOrderDomainAcrossFourLayers() {
        List<ClassRoleRecord> records = List.of(
            r("com.example.order.controller.OrderController", "CONTROLLER"),
            r("com.example.order.service.OrderServiceImpl", "SERVICE"),
            r("com.example.order.repo.OrderRepository", "REPOSITORY"),
            r("com.example.order.model.OrderEntity", "MODEL"));

        VerticalDomainResult result = aggregator.aggregate(records);

        assertThat(result.domains()).hasSize(1);
        VerticalDomain order = result.domains().get(0);
        assertThat(order.domainWord()).isEqualTo("Order");
        assertThat(order.complete()).isTrue();
        assertThat(order.members()).hasSize(4);
        assertThat(order.roles()).containsExactlyInAnyOrder("CONTROLLER", "SERVICE", "REPOSITORY", "MODEL");
        assertThat(result.isolatedDataObjects()).isEmpty();
    }

    @Test
    @DisplayName("领域跨越包边界：controller/service/repo/model 不同包仍聚合为一个领域")
    void aggregatesAcrossPackageBoundary() {
        List<ClassRoleRecord> records = List.of(
            r("com.example.web.OrderController", "CONTROLLER"),
            r("com.example.biz.OrderService", "SERVICE"),
            r("com.example.data.OrderMapper", "REPOSITORY"),
            r("com.example.po.OrderPO", "MODEL"));

        VerticalDomainResult result = aggregator.aggregate(records);

        assertThat(result.domains()).hasSize(1);
        assertThat(result.domains().get(0).domainWord()).isEqualTo("Order");
        assertThat(result.domains().get(0).roles())
            .containsExactlyInAnyOrder("CONTROLLER", "SERVICE", "REPOSITORY", "MODEL");
    }

    @Test
    @DisplayName("多个领域各自聚合：Order 域与 Payment 域并列")
    void aggregatesMultipleDomains() {
        List<ClassRoleRecord> records = List.of(
            r("OrderController", "CONTROLLER"),
            r("OrderService", "SERVICE"),
            r("OrderEntity", "MODEL"),
            r("PaymentController", "CONTROLLER"),
            r("PaymentService", "SERVICE"),
            r("PaymentRepository", "REPOSITORY"));

        VerticalDomainResult result = aggregator.aggregate(records);

        assertThat(result.domains()).extracting(VerticalDomain::domainWord)
            .containsExactlyInAnyOrder("Order", "Payment");
    }

    @Test
    @DisplayName("纯 MODEL 孤立词（只有数据对象无业务类）归孤立数据对象，不入域")
    void pureModelIsIsolated() {
        List<ClassRoleRecord> records = List.of(
            r("OrderEntity", "MODEL"),
            r("OrderDTO", "MODEL"));

        VerticalDomainResult result = aggregator.aggregate(records);

        assertThat(result.domains()).isEmpty();
        assertThat(result.isolatedDataObjects()).hasSize(1);
        assertThat(result.isolatedDataObjects().get(0).domainWord()).isEqualTo("Order");
        assertThat(result.isolatedDataObjects().get(0).complete()).isFalse();
    }

    @Test
    @DisplayName("单角色孤立词（只有 OrderEntity 无 OrderService）不入域")
    void singleRoleIsIsolated() {
        VerticalDomainResult result = aggregator.aggregate(List.of(r("OrderEntity", "MODEL")));

        assertThat(result.domains()).isEmpty();
        assertThat(result.isolatedDataObjects()).hasSize(1);
    }

    @Test
    @DisplayName("含业务角色但单 Controller（仅 1 个分层角色）不入域")
    void singleControllerIsIsolated() {
        VerticalDomainResult result = aggregator.aggregate(List.of(r("OrderController", "CONTROLLER")));

        assertThat(result.domains()).isEmpty();
        assertThat(result.isolatedDataObjects()).hasSize(1);
    }

    @Test
    @DisplayName("MODEL+UTILITY 两角色但无业务角色 → 不入域（骨架判定剔除）")
    void modelPlusUtilityWithoutBusinessRoleIsIsolated() {
        List<ClassRoleRecord> records = List.of(
            r("OrderEntity", "MODEL"),
            r("OrderUtil", "UTILITY"));

        VerticalDomainResult result = aggregator.aggregate(records);

        assertThat(result.domains()).isEmpty();
        assertThat(result.isolatedDataObjects()).hasSize(1);
    }

    @Test
    @DisplayName("UNKNOWN 角色不参与角色计数：OrderController + OrderHelper(UNKNOWN) 仍单业务角色不入域")
    void unknownRoleDoesNotCountTowardSpanning() {
        List<ClassRoleRecord> records = List.of(
            r("OrderController", "CONTROLLER"),
            r("OrderHelper", "UNKNOWN"));

        VerticalDomainResult result = aggregator.aggregate(records);

        // 有效角色只有 CONTROLLER（UNKNOWN 剔除），单角色 → 不入域
        assertThat(result.domains()).isEmpty();
        assertThat(result.isolatedDataObjects()).hasSize(1);
        assertThat(result.isolatedDataObjects().get(0).roles()).containsExactly("CONTROLLER");
    }
}
