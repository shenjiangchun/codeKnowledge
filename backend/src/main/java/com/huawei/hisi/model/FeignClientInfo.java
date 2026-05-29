package com.huawei.hisi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Feign client information model.
 * Stores metadata about a Feign client interface and its methods.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeignClientInfo {

    /**
     * Feign client interface name (full qualified)
     */
    private String interfaceName;

    /**
     * Target service name (from @FeignClient name or value)
     */
    private String serviceName;

    /**
     * Target service URL (from @FeignClient url)
     */
    private String serviceUrl;

    /**
     * Base path (from @FeignClient path)
     */
    private String basePath;

    /**
     * Method name
     */
    private String methodName;

    /**
     * HTTP method: GET, POST, PUT, DELETE, PATCH
     */
    private String httpMethod;

    /**
     * URI pattern for this method
     */
    private String uriPattern;

    /**
     * Full URI including base path
     */
    private String fullUri;

    /**
     * Method signature
     */
    private String methodSignature;

    /**
     * Project package name
     */
    private String packageName;

    /**
     * Additional attributes from @FeignClient
     */
    private Map<String, String> attributes;

    /**
     * Build full URI from base path and method URI
     */
    public String getFullUri() {
        if (fullUri != null && !fullUri.isEmpty()) {
            return fullUri;
        }
        StringBuilder sb = new StringBuilder();
        if (basePath != null && !basePath.isEmpty()) {
            if (!basePath.startsWith("/")) {
                sb.append("/");
            }
            sb.append(basePath);
        }
        if (uriPattern != null && !uriPattern.isEmpty()) {
            if (!uriPattern.startsWith("/") && sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') {
                sb.append("/");
            }
            sb.append(uriPattern);
        }
        return sb.toString();
    }
}