package com.huawei.hisi.loganalysis.nodes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ParseNode - the first node in log analysis DAG.
 */
class ParseNodeTest {

    private final ParseNode parseNode = new ParseNode();

    @Test
    @DisplayName("parse basic NullPointerException")
    void parseNullPointerException() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Error processing request");
        input.put("stackTrace", """
java.lang.NullPointerException: Cannot invoke method on null object
	at com.example.service.UserService.getUser(UserService.java:42)
	at com.example.controller.UserController.handleRequest(UserController.java:25)
	at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:897)
Caused by: java.lang.IllegalArgumentException: Invalid parameter
	at com.example.validation.Validator.check(Validator.java:15)
""");

        Map<String, Object> output = parseNode.execute(input);

        assertThat(output).containsKeys("parsedError", "keyFrames", "searchTerms", "errorFingerprint");

        Map<String, Object> parsedError = (Map<String, Object>) output.get("parsedError");
        // errorType is the LAST exception found (IllegalArgumentException from Caused by)
        assertThat(parsedError.get("errorType")).isEqualTo("IllegalArgumentException");
        assertThat(parsedError.get("rootCauseException")).isEqualTo("IllegalArgumentException");

        List<Map<String, Object>> keyFrames = (List<Map<String, Object>>) output.get("keyFrames");
        assertThat(keyFrames).hasSize(3); // UserService, UserController, Validator (non-framework)
        assertThat(keyFrames.get(0).get("simpleClassName")).isEqualTo("UserService");
        assertThat(keyFrames.get(0).get("methodName")).isEqualTo("getUser");

        List<String> searchTerms = (List<String>) output.get("searchTerms");
        assertThat(searchTerms).contains("com.example.service.UserService", "com.example.service.UserService.getUser");
    }

    @Test
    @DisplayName("parse error without Caused by")
    void parseErrorWithoutCausedBy() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Connection failed");
        input.put("stackTrace", """
java.net.SocketTimeoutException: Read timed out
	at java.net.SocketInputStream.read(SocketInputStream.java:150)
	at com.example.network.HttpClient.execute(HttpClient.java:88)
	at sun.nio.ch.Net.read(Net.java:400)
""");

        Map<String, Object> output = parseNode.execute(input);

        Map<String, Object> parsedError = (Map<String, Object>) output.get("parsedError");
        assertThat(parsedError.get("errorType")).isEqualTo("SocketTimeoutException");
        assertThat(parsedError.get("rootCauseException")).isEqualTo("SocketTimeoutException");

        // Only HttpClient is non-framework
        List<Map<String, Object>> keyFrames = (List<Map<String, Object>>) output.get("keyFrames");
        assertThat(keyFrames).hasSize(1);
        assertThat(keyFrames.get(0).get("simpleClassName")).isEqualTo("HttpClient");
    }

    @Test
    @DisplayName("parse empty stack trace")
    void parseEmptyStackTrace() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Unknown error occurred");
        input.put("stackTrace", "");

        Map<String, Object> output = parseNode.execute(input);

        Map<String, Object> parsedError = (Map<String, Object>) output.get("parsedError");
        assertThat(parsedError.get("errorType")).isEqualTo("Unknown");
        assertThat(parsedError.get("rootCauseException")).isNull();

        List<Map<String, Object>> keyFrames = (List<Map<String, Object>>) output.get("keyFrames");
        assertThat(keyFrames).isEmpty();
    }

    @Test
    @DisplayName("build fingerprint for same errors")
    void buildFingerprint() {
        Map<String, Object> input1 = new LinkedHashMap<>();
        input1.put("message", "Error");
        input1.put("stackTrace", """
java.lang.NullPointerException
	at com.example.Service.method(Service.java:10)
	at com.example.Handler.handle(Handler.java:20)
""");

        Map<String, Object> input2 = new LinkedHashMap<>();
        input2.put("message", "Different message but same stack");
        input2.put("stackTrace", """
java.lang.NullPointerException
	at com.example.Service.method(Service.java:10)
	at com.example.Handler.handle(Handler.java:20)
""");

        Map<String, Object> output1 = parseNode.execute(input1);
        Map<String, Object> output2 = parseNode.execute(input2);

        // Same error type and key frames should produce same fingerprint
        assertThat(output1.get("errorFingerprint")).isEqualTo(output2.get("errorFingerprint"));
    }

    @Test
    @DisplayName("filter framework classes correctly")
    void filterFrameworkClasses() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Error");
        input.put("stackTrace", """
java.lang.RuntimeException: Application error
	at java.util.ArrayList.get(ArrayList.java:100)
	at javax.servlet.http.HttpServlet.service(HttpServlet.java:50)
	at sun.misc.Unsafe.get(Unsafe.java:10)
	at org.springframework.beans.BeanWrapper.getValue(BeanWrapper.java:30)
	at org.apache.commons.lang.StringUtils.trim(StringUtils.java:20)
	at com.fasterxml.jackson.databind.ObjectMapper.readValue(ObjectMapper.java:100)
	at io.netty.channel.Channel.read(Channel.java:50)
	at reactor.core.publisher.Mono.just(Mono.java:10)
	at com.myapp.MyService.doWork(MyService.java:25)
""");

        Map<String, Object> output = parseNode.execute(input);

        List<Map<String, Object>> keyFrames = (List<Map<String, Object>>) output.get("keyFrames");
        // Only MyService should survive (non-framework)
        assertThat(keyFrames).hasSize(1);
        assertThat(keyFrames.get(0).get("simpleClassName")).isEqualTo("MyService");
    }

    @Test
    @DisplayName("prioritize project package prefixes when provided")
    void prioritizeProjectPackagePrefixes() {
        // Simulate production scenario: project code buried deep after AWS SDK/Feign/Jakarta frames
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Request processing failed");
        input.put("stackTrace", """
feign.RetryableException: Error executing request
	at feign.SynchronousMethodHandler.executeAndDecode(SynchronousMethodHandler.java:108)
	at feign.SynchronousMethodHandler.invoke(SynchronousMethodHandler.java:78)
	at feign.ReflectiveFeign$FeignInvocationHandler.invoke(ReflectiveFeign.java:103)
	at com.sun.proxy.$Proxy153.handleRequest(Unknown Source)
	at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:590)
	at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:665)
	at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:1002)
	at org.apache.coyote.http11.Http11Processor.service(Http11Processor.java:261)
	at org.apache.coyote.AbstractProcessorLight.process(AbstractProcessorLight.java:63)
	at org.apache.coyote.AbstractProtocol$ConnectionHandler.process(AbstractProtocol.java:934)
	at org.apache.tomcat.util.net.NioEndpoint$SocketProcessor.doRun(NioEndpoint.java:1787)
	at org.apache.tomcat.util.net.SocketProcessorBase.run(SocketProcessorBase.java:49)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1135)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
	at org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:61)
	at java.lang.Thread.run(Thread.java:833)
	at com.hisilicon.product.service.ProductService.getProduct(ProductService.java:125)
	at com.hisilicon.product.controller.ProductController.handleQuery(ProductController.java:45)
	at com.hisilicon.common.filter.RequestFilter.doFilter(RequestFilter.java:30)
	at com.other.vendor.VendorService.processVendor(VendorService.java:88)
""");
        // Project package prefix configuration
        input.put("projectPackagePrefixes", List.of("com.hisilicon"));

        Map<String, Object> output = parseNode.execute(input);

        List<Map<String, Object>> keyFrames = (List<Map<String, Object>>) output.get("keyFrames");

        // Project frames should be extracted first even though they appear after 20+ framework frames
        assertThat(keyFrames).isNotEmpty();
        assertThat(keyFrames).hasSize(4); // ProductService, ProductController, RequestFilter, VendorService

        // First three frames should be project-specific (com.hisilicon.*)
        assertThat((String) keyFrames.get(0).get("className")).startsWith("com.hisilicon");
        assertThat(keyFrames.get(0).get("simpleClassName")).isEqualTo("ProductService");
        assertThat(keyFrames.get(1).get("simpleClassName")).isEqualTo("ProductController");
        assertThat(keyFrames.get(2).get("simpleClassName")).isEqualTo("RequestFilter");

        // Fourth frame should be other non-framework (com.other.vendor)
        assertThat(keyFrames.get(3).get("simpleClassName")).isEqualTo("VendorService");
    }

    @Test
    @DisplayName("without projectPackagePrefixes, extract non-framework frames in order")
    void withoutProjectPrefixesExtractsInOrder() {
        // Same stack trace but without projectPackagePrefixes
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Request processing failed");
        input.put("stackTrace", """
feign.RetryableException: Error executing request
	at feign.SynchronousMethodHandler.executeAndDecode(SynchronousMethodHandler.java:108)
	at com.other.vendor.VendorService.processVendor(VendorService.java:88)
	at com.hisilicon.product.service.ProductService.getProduct(ProductService.java:125)
	at com.hisilicon.product.controller.ProductController.handleQuery(ProductController.java:45)
""");

        Map<String, Object> output = parseNode.execute(input);

        List<Map<String, Object>> keyFrames = (List<Map<String, Object>>) output.get("keyFrames");

        // Without prefixes, non-framework frames extracted in order of appearance
        assertThat(keyFrames).hasSize(3);
        assertThat(keyFrames.get(0).get("simpleClassName")).isEqualTo("VendorService");
        assertThat(keyFrames.get(1).get("simpleClassName")).isEqualTo("ProductService");
        assertThat(keyFrames.get(2).get("simpleClassName")).isEqualTo("ProductController");
    }
}