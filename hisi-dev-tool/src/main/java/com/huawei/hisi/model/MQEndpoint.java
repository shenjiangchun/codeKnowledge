package com.huawei.hisi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MQ endpoint model representing a message queue producer or consumer.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MQEndpoint {

    /**
     * MQ type: KAFKA, RABBITMQ, ROCKETMQ, JMS
     */
    private MQType mqType;

    /**
     * Topic or queue name
     */
    private String topic;

    /**
     * Consumer group ID (for Kafka/RocketMQ)
     */
    private String consumerGroup;

    /**
     * Source method (producer) - the method that sends messages
     */
    private String sourceMethod;

    /**
     * Source class (producer)
     */
    private String sourceClass;

    /**
     * Target method (consumer) - the method that receives messages
     */
    private String targetMethod;

    /**
     * Target class (consumer)
     */
    private String targetClass;

    /**
     * Project package name
     */
    private String packageName;

    /**
     * Endpoint type: PRODUCER or CONSUMER
     */
    private EndpointType endpointType;

    /**
     * Additional metadata as JSON string
     */
    private String metadata;

    /**
     * MQ type enumeration
     */
    public enum MQType {
        KAFKA,
        RABBITMQ,
        ROCKETMQ,
        JMS
    }

    /**
     * Endpoint type enumeration
     */
    public enum EndpointType {
        PRODUCER,
        CONSUMER
    }
}