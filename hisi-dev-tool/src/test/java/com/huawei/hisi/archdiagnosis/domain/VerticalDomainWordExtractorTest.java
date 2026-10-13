package com.huawei.hisi.archdiagnosis.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 领域词提取单测：剥离技术后缀得核心领域词；复合词不拆分（留给 LLM 消歧）；
 * 缩写/无后缀回退原文；短全大写后缀（DO/PO/VO）大小写敏感不误伤普通词。
 */
class VerticalDomainWordExtractorTest {

    private final VerticalDomainWordExtractor extractor = new VerticalDomainWordExtractor();

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '→', value = {
        "com.example.order.OrderController → Order",
        "com.example.order.OrderServiceImpl → Order",
        "OrderRepository → Order",
        "OrderDao → Order",
        "OrderMapper → Order",
        "OrderEntity → Order",
        "OrderPO → Order",
        "OrderDO → Order",
        "OrderDTO → Order",
        "OrderVO → Order",
        "OrderQO → Order",
        "OrderBO → Order",
        "OrderModel → Order",
        "OrderEnum → Order",
        "OrderUtil → Order",
        "OrderUtils → Order",
        "OrderConfig → Order",
        "OrderConfiguration → Order",
        "OrderConstant → Order",
        "OrderConstants → Order",
        "OrderHelper → Order",
        "OrderFactory → Order",
        "OrderBuilder → Order",
        "OrderHandler → Order",
        "OrderListener → Order",
        "OrderFilter → Order",
        "OrderInterceptor → Order",
        "OrderAspect → Order",
        "OrderException → Order",
        "OrderRequest → Order",
        "OrderResponse → Order",
        "OrderImpl → Order",
    })
    @DisplayName("剥离技术后缀得核心领域词")
    void stripsTechnicalSuffix(String className, String expected) {
        assertThat(extractor.extract(className)).isEqualTo(expected);
    }

    @Test
    @DisplayName("复合词不拆分：OrderPaymentService → OrderPayment（拆分交给 LLM 消歧）")
    void keepsCompoundWord() {
        assertThat(extractor.extract("OrderPaymentService")).isEqualTo("OrderPayment");
        assertThat(extractor.extract("UserOrderDTO")).isEqualTo("UserOrder");
    }

    @Test
    @DisplayName("缩写无匹配后缀回退原文：OrdSvc → OrdSvc")
    void abbreviationFallsBack() {
        assertThat(extractor.extract("OrdSvc")).isEqualTo("OrdSvc");
    }

    @Test
    @DisplayName("无后缀回退原文：OrderProcessor → OrderProcessor")
    void noSuffixFallsBack() {
        assertThat(extractor.extract("OrderProcessor")).isEqualTo("OrderProcessor");
    }

    @Test
    @DisplayName("短全大写后缀大小写敏感：Pseudo 不被 DO 误伤")
    void shortUpperCaseSuffixDoesNotOvermatch() {
        assertThat(extractor.extract("Pseudo")).isEqualTo("Pseudo");
    }

    @Test
    @DisplayName("空类名回退空串，不抛异常")
    void blankClassNameReturnsBlank() {
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("")).isEmpty();
    }

    @Test
    @DisplayName("纯后缀类名剥离后为空回退原文：Controller → Controller")
    void bareSuffixFallsBackToOriginal() {
        assertThat(extractor.extract("Controller")).isEqualTo("Controller");
    }
}
