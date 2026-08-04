package com.huawei.hisi.cache;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global analysis cache for storing shared data across analysis components.
 * This class centralizes all cache maps that were previously static variables
 * in HisiURIMethodChainToDBServiceImpl.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Component
public class GlobalAnalysisCache {

    // ============================================================
    // Core Analysis Caches (from HisiURIMethodChainToDBServiceImpl)
    // ============================================================

    /**
     * Bean name to source file path mapping
     * Key: class name (full qualified name), Value: source file path
     */
    private final Map<String, Path> beanMap = new ConcurrentHashMap<>();

    /**
     * Class to parent class/interface mapping
     * Key: class name, Value: set of parent class/interface names
     */
    private final Map<String, Set<String>> extendMap = new ConcurrentHashMap<>();

    /**
     * Interface to implementation classes mapping
     * Key: interface name, Value: set of implementation class names
     */
    private final Map<String, Set<String>> implementationMap = new ConcurrentHashMap<>();

    /**
     * URI to controller method mapping
     * Key: URI path, Value: MethodDeclaration
     */
    private final Map<String, MethodDeclaration> uriMap = new ConcurrentHashMap<>();

    /**
     * Combined type solver for symbol resolution
     */
    private CombinedTypeSolver typeSolver;

    // ============================================================
    // MQ Endpoint Caches
    // ============================================================

    /**
     * Kafka listener endpoints
     * Key: topic name, Value: set of consumer method signatures
     */
    private final Map<String, Set<String>> kafkaEndpoints = new ConcurrentHashMap<>();

    /**
     * RabbitMQ listener endpoints
     * Key: queue name, Value: set of consumer method signatures
     */
    private final Map<String, Set<String>> rabbitEndpoints = new ConcurrentHashMap<>();

    /**
     * RocketMQ listener endpoints
     * Key: topic name, Value: set of consumer method signatures
     */
    private final Map<String, Set<String>> rocketMQEndpoints = new ConcurrentHashMap<>();

    /**
     * JMS listener endpoints
     * Key: destination name, Value: set of consumer method signatures
     */
    private final Map<String, Set<String>> jmsEndpoints = new ConcurrentHashMap<>();

    // ============================================================
    // HTTP/Feign Client Caches
    // ============================================================

    /**
     * Feign client definitions
     * Key: service name, Value: set of FeignClientInfo objects (serialized as JSON)
     */
    private final Map<String, Set<String>> feignClientMap = new ConcurrentHashMap<>();

    /**
     * REST Controller endpoints
     * Key: service name + URI pattern, Value: handler method signature
     */
    private final Map<String, String> restEndpointMap = new ConcurrentHashMap<>();

    // ============================================================
    // Proxy Metadata Caches
    // ============================================================

    /**
     * MyBatis Mapper interfaces
     * Key: interface name, Value: set of method signatures with SQL info
     */
    private final Map<String, Set<String>> myBatisMapperMap = new ConcurrentHashMap<>();

    /**
     * JPA Repository interfaces
     * Key: interface name, Value: entity type
     */
    private final Map<String, String> jpaRepositoryMap = new ConcurrentHashMap<>();

    /**
     * AOP Aspect classes
     * Key: aspect class name, Value: set of pointcut definitions
     */
    private final Map<String, Set<String>> aspectMap = new ConcurrentHashMap<>();

    // ============================================================
    // Bridge Index Maps (for efficient cross-boundary lookup)
    // ============================================================

    /**
     * MQ Consumer index - maps topic to consumer method signatures
     * Key: topic name, Value: list of consumer method signatures
     */
    private final Map<String, List<String>> mqConsumerIndex = new ConcurrentHashMap<>();

    /**
     * MQ Producer index - maps topic to producer method signatures
     * Key: topic name, Value: list of producer method signatures
     */
    private final Map<String, List<String>> mqProducerIndex = new ConcurrentHashMap<>();

    /**
     * Feign URI index - maps service+URI to Feign client method info
     * Key: serviceName + "|" + uri, Value: FeignClientInfo as string
     */
    private final Map<String, String> feignUriIndex = new ConcurrentHashMap<>();

    /**
     * Proxy index - maps interface name to proxy metadata
     * Key: interface name, Value: proxy type (MYBATIS/JPA/AOP)
     */
    private final Map<String, String> proxyIndex = new ConcurrentHashMap<>();

    // ============================================================
    // Bean Name Index (for @Qualifier resolution)
    // ============================================================

    /**
     * Bean name to FQN class name mapping.
     * Key: bean name (from @Component("name") / @Service("name") or default lcfirst simple name),
     * Value: FQN class name
     */
    private final Map<String, String> beanNameMap = new ConcurrentHashMap<>();

    // ============================================================
    // Getters for Core Caches
    // ============================================================

    public Map<String, Path> getBeanMap() {
        return beanMap;
    }

    public Map<String, Set<String>> getExtendMap() {
        return extendMap;
    }

    public Map<String, Set<String>> getImplementationMap() {
        return implementationMap;
    }

    public Map<String, MethodDeclaration> getUriMap() {
        return uriMap;
    }

    public CombinedTypeSolver getTypeSolver() {
        return typeSolver;
    }

    public void setTypeSolver(CombinedTypeSolver typeSolver) {
        this.typeSolver = typeSolver;
    }

    // ============================================================
    // Getters for MQ Caches
    // ============================================================

    public Map<String, Set<String>> getKafkaEndpoints() {
        return kafkaEndpoints;
    }

    public Map<String, Set<String>> getRabbitEndpoints() {
        return rabbitEndpoints;
    }

    public Map<String, Set<String>> getRocketMQEndpoints() {
        return rocketMQEndpoints;
    }

    public Map<String, Set<String>> getJmsEndpoints() {
        return jmsEndpoints;
    }

    // ============================================================
    // Getters for HTTP/Feign Caches
    // ============================================================

    public Map<String, Set<String>> getFeignClientMap() {
        return feignClientMap;
    }

    public Map<String, String> getRestEndpointMap() {
        return restEndpointMap;
    }

    // ============================================================
    // Getters for Proxy Caches
    // ============================================================

    public Map<String, Set<String>> getMyBatisMapperMap() {
        return myBatisMapperMap;
    }

    public Map<String, String> getJpaRepositoryMap() {
        return jpaRepositoryMap;
    }

    public Map<String, Set<String>> getAspectMap() {
        return aspectMap;
    }

    // ============================================================
    // Getters for Bridge Index Maps
    // ============================================================

    public Map<String, List<String>> getMqConsumerIndex() {
        return mqConsumerIndex;
    }

    public Map<String, List<String>> getMqProducerIndex() {
        return mqProducerIndex;
    }

    public Map<String, String> getFeignUriIndex() {
        return feignUriIndex;
    }

    public Map<String, String> getProxyIndex() {
        return proxyIndex;
    }

    // ============================================================
    // Getters for Bean Name Index
    // ============================================================

    public Map<String, String> getBeanNameMap() {
        return beanNameMap;
    }

    // ============================================================
    // Utility Methods
    // ============================================================

    /**
     * Clear all caches
     */
    public void clearAll() {
        beanMap.clear();
        extendMap.clear();
        implementationMap.clear();
        uriMap.clear();
        kafkaEndpoints.clear();
        rabbitEndpoints.clear();
        rocketMQEndpoints.clear();
        jmsEndpoints.clear();
        feignClientMap.clear();
        restEndpointMap.clear();
        myBatisMapperMap.clear();
        jpaRepositoryMap.clear();
        aspectMap.clear();
        mqConsumerIndex.clear();
        mqProducerIndex.clear();
        feignUriIndex.clear();
        proxyIndex.clear();
        beanNameMap.clear();
        typeSolver = null;
    }

    /**
     * Clear only the bridge caches (MQ, HTTP, Proxy)
     */
    public void clearBridgeCaches() {
        kafkaEndpoints.clear();
        rabbitEndpoints.clear();
        rocketMQEndpoints.clear();
        jmsEndpoints.clear();
        feignClientMap.clear();
        restEndpointMap.clear();
        myBatisMapperMap.clear();
        jpaRepositoryMap.clear();
        aspectMap.clear();
        mqConsumerIndex.clear();
        mqProducerIndex.clear();
        feignUriIndex.clear();
        proxyIndex.clear();
    }

    /**
     * Get all MQ endpoints merged into a single map
     * @return merged map with topic/queue as key and consumer methods as value
     */
    public Map<String, Set<String>> getAllMQEndpoints() {
        Map<String, Set<String>> all = new ConcurrentHashMap<>();
        all.putAll(kafkaEndpoints);
        all.putAll(rabbitEndpoints);
        all.putAll(rocketMQEndpoints);
        all.putAll(jmsEndpoints);
        return all;
    }

    /**
     * Check if cache is initialized
     * @return true if core caches are populated
     */
    public boolean isInitialized() {
        return !beanMap.isEmpty() || !uriMap.isEmpty();
    }
}