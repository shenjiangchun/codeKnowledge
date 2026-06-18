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
}