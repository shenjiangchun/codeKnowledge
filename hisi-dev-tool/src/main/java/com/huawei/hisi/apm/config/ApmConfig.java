package com.huawei.hisi.apm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * APM module configuration properties, bound from the {@code apm.*} namespace
 * in {@code application.yml}.
 */
@Data
@Component
@ConfigurationProperties(prefix = "apm")
public class ApmConfig {

    /**
     * Absolute path to the OpenTelemetry Java agent JAR.
     * When left blank the runtime resolves a default location.
     */
    private String otelAgentPath;

    /**
     * OTel Java agent version to download / verify.
     */
    private String otelAgentVersion = "2.14.0";

    /**
     * How long (in seconds) to wait for the target JVM to become ready
     * after attaching the OTel agent.
     */
    private int targetReadyTimeoutSeconds = 60;

    /**
     * Time-to-live (in hours) for stored spans before they are purged.
     */
    private int spanTtlHours = 24;

    /**
     * Grace period (in seconds) for the target process to shut down
     * cleanly after receiving a stop signal.
     */
    private int targetShutdownGraceSeconds = 5;

    /**
     * Port for the OTLP HTTP receiver.
     * {@code 0} means reuse the Spring Boot server port.
     */
    private int otlpReceiverPort = 0;
}
