package com.huawei.hisi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HTTP call information model.
 * Represents an HTTP call made via RestTemplate or WebClient.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HttpCallInfo {

    /**
     * The HTTP client type (RestTemplate or WebClient)
     */
    private String clientType;

    /**
     * The class that makes the HTTP call
     */
    private String sourceClass;

    /**
     * The method that makes the HTTP call (className.methodName)
     */
    private String sourceMethod;

    /**
     * HTTP method (GET, POST, PUT, DELETE, etc.)
     */
    private String httpMethod;

    /**
     * The URL or URI pattern of the HTTP call
     */
    private String url;

    /**
     * The package name of the source class
     */
    private String packageName;
}