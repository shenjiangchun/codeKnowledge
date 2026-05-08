package com.huawei.hisi.service.impact.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Caller model representing a method that calls the changed method.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Caller {

    /**
     * Full qualified class name of the caller
     */
    private String className;

    /**
     * Method name of the caller
     */
    private String methodName;

    /**
     * Full method signature
     */
    private String methodSignature;

    /**
     * Line number where the call occurs
     */
    private Integer callLineNumber;

    /**
     * Source file path
     */
    private String filePath;

    /**
     * Caller type: CONTROLLER/SERVICE/DAO/UTIL/MQ_ENDPOINT/etc.
     */
    private CallerType callerType;

    /**
     * Whether this caller is an entry point (HTTP endpoint, MQ consumer, etc.)
     */
    private boolean isEntryPoint;

    /**
     * Associated URI if this caller is an HTTP endpoint
     */
    private String associatedUri;

    /**
     * Associated MQ topic/queue if this caller is an MQ endpoint
     */
    private String associatedMQEndpoint;

    /**
     * Call frequency (estimated number of calls in production)
     */
    private Integer callFrequency;

    /**
     * Description of the call context
     */
    private String contextDescription;

    /**
     * Caller type enumeration
     */
    public enum CallerType {
        /**
         * REST Controller endpoint
         */
        CONTROLLER,

        /**
         * Service layer method
         */
        SERVICE,

        /**
         * Data access layer method
         */
        DAO,

        /**
         * Utility class method
         */
        UTIL,

        /**
         * MQ consumer endpoint
         */
        MQ_ENDPOINT,

        /**
         * Feign client call
         */
        FEIGN_CLIENT,

        /**
         * Scheduled task
         */
        SCHEDULED,

        /**
         * Unknown caller type
         */
        UNKNOWN
    }
}