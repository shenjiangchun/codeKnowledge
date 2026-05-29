package com.huawei.hisi.scanner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.model.MQEndpoint;
import com.huawei.hisi.model.ScanResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MQ endpoint scanner for detecting message queue producers and consumers.
 * Supports: Kafka, RabbitMQ, RocketMQ, JMS
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Component
public class MQEndpointScanner implements EndpointScanner<MQEndpoint> {

    private static final Logger LOG = Logger.getLogger(MQEndpointScanner.class.getName());

    // Consumer annotations
    private static final String KAFKA_LISTENER = "KafkaListener";
    private static final String RABBIT_LISTENER = "RabbitListener";
    private static final String ROCKETMQ_LISTENER = "RocketMQMessageListener";
    private static final String JMS_LISTENER = "JmsListener";

    // Producer patterns (method calls)
    private static final Set<String> PRODUCER_PATTERNS = Set.of(
            "kafkaTemplate.send",
            "kafkaTemplate.sendDefault",
            "rabbitTemplate.convertAndSend",
            "rabbitTemplate.send",
            "rocketMQTemplate.syncSend",
            "rocketMQTemplate.asyncSend",
            "jmsTemplate.send",
            "jmsTemplate.convertAndSend"
    );

    private final JavaParser javaParser;
    private final Set<String> supportedAnnotations;

    public MQEndpointScanner() {
        ParserConfiguration config = new ParserConfiguration();
        this.javaParser = new JavaParser(config);
        this.supportedAnnotations = Set.of(
                KAFKA_LISTENER, RABBIT_LISTENER, ROCKETMQ_LISTENER, JMS_LISTENER
        );
    }

    @Override
    public String getScannerName() {
        return "MQEndpointScanner";
    }

    @Override
    public ScanResult<MQEndpoint> scanFile(Path filePath, GlobalAnalysisCache globalCache) {
        long startTime = System.currentTimeMillis();
        List<MQEndpoint> endpoints = new ArrayList<>();

        try {
            Optional<CompilationUnit> cuOpt = javaParser.parse(filePath).getResult();
            if (cuOpt.isEmpty()) {
                return ScanResult.failure("Failed to parse file: " + filePath, getScannerName());
            }

            CompilationUnit cu = cuOpt.get();
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            // Get class name
            String[] classNameHolder = {""};
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> {
                classNameHolder[0] = c.getFullyQualifiedName().orElse(c.getNameAsString());
            });

            String className = classNameHolder[0];

            // Visit all methods and look for MQ annotations
            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(MethodDeclaration method, Void arg) {
                    super.visit(method, arg);

                    // Check for consumer annotations
                    for (AnnotationExpr annotation : method.getAnnotations()) {
                        MQEndpoint endpoint = parseConsumerAnnotation(annotation, method, className, packageName);
                        if (endpoint != null) {
                            endpoints.add(endpoint);
                        }
                    }

                    // Check for producer patterns in method body
                    method.getBody().ifPresent(body -> {
                        String bodyStr = body.toString();
                        for (String pattern : PRODUCER_PATTERNS) {
                            if (bodyStr.contains(pattern)) {
                                MQEndpoint producer = MQEndpoint.builder()
                                        .mqType(detectMQType(pattern))
                                        .endpointType(MQEndpoint.EndpointType.PRODUCER)
                                        .sourceMethod(className + "." + method.getNameAsString())
                                        .sourceClass(className)
                                        .packageName(packageName)
                                        .build();
                                endpoints.add(producer);
                            }
                        }
                    });
                }
            }, null);

        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error scanning file: " + filePath, e);
            return ScanResult.failure("IO error: " + e.getMessage(), getScannerName());
        }

        long duration = System.currentTimeMillis() - startTime;

        return ScanResult.<MQEndpoint>builder()
                .success(true)
                .items(endpoints)
                .foundCount(endpoints.size())
                .scannedCount(1)
                .durationMs(duration)
                .scannerType(getScannerName())
                .build();
    }

    @Override
    public ScanResult<MQEndpoint> scanFiles(List<Path> filePaths, GlobalAnalysisCache globalCache) {
        long startTime = System.currentTimeMillis();
        List<MQEndpoint> allEndpoints = new ArrayList<>();
        int scannedCount = 0;

        for (Path filePath : filePaths) {
            if (canScan(filePath)) {
                ScanResult<MQEndpoint> result = scanFile(filePath, globalCache);
                if (result.isSuccess() && result.getItems() != null) {
                    allEndpoints.addAll(result.getItems());
                }
                scannedCount++;
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        // Update global cache
        updateGlobalCache(allEndpoints, globalCache);

        return ScanResult.<MQEndpoint>builder()
                .success(true)
                .items(allEndpoints)
                .foundCount(allEndpoints.size())
                .scannedCount(scannedCount)
                .durationMs(duration)
                .scannerType(getScannerName())
                .build();
    }

    @Override
    public boolean canScan(Path filePath) {
        String fileName = filePath.toString().toLowerCase();
        return fileName.endsWith(".java");
    }

    @Override
    public Set<String> getSupportedAnnotations() {
        return supportedAnnotations;
    }

    /**
     * Parse consumer annotation and extract endpoint info
     */
    private MQEndpoint parseConsumerAnnotation(AnnotationExpr annotation, MethodDeclaration method,
                                                String className, String packageName) {
        String annotationName = annotation.getNameAsString();

        MQEndpoint.MQType mqType = null;
        String topic = null;
        String consumerGroup = null;

        switch (annotationName) {
            case KAFKA_LISTENER:
                mqType = MQEndpoint.MQType.KAFKA;
                topic = extractAnnotationValue(annotation, "topics", "value");
                consumerGroup = extractAnnotationValue(annotation, "groupId", "containerFactory");
                break;

            case RABBIT_LISTENER:
                mqType = MQEndpoint.MQType.RABBITMQ;
                topic = extractAnnotationValue(annotation, "queues", "value");
                break;

            case ROCKETMQ_LISTENER:
                mqType = MQEndpoint.MQType.ROCKETMQ;
                topic = extractAnnotationValue(annotation, "topic", "value");
                consumerGroup = extractAnnotationValue(annotation, "consumerGroup", "selectorExpression");
                break;

            case JMS_LISTENER:
                mqType = MQEndpoint.MQType.JMS;
                topic = extractAnnotationValue(annotation, "destination", "value");
                break;

            default:
                return null;
        }

        if (topic == null || topic.isEmpty()) {
            return null;
        }

        // Clean up topic string (remove array brackets, quotes)
        topic = cleanTopicString(topic);

        return MQEndpoint.builder()
                .mqType(mqType)
                .topic(topic)
                .consumerGroup(consumerGroup)
                .endpointType(MQEndpoint.EndpointType.CONSUMER)
                .targetMethod(className + "." + method.getNameAsString())
                .targetClass(className)
                .packageName(packageName)
                .build();
    }

    /**
     * Extract value from annotation by attribute name
     */
    private String extractAnnotationValue(AnnotationExpr annotation, String... attrNames) {
        if (annotation instanceof SingleMemberAnnotationExpr) {
            return ((SingleMemberAnnotationExpr) annotation).getMemberValue().toString();
        }

        if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr nae = (NormalAnnotationExpr) annotation;
            for (MemberValuePair pair : nae.getPairs()) {
                for (String attrName : attrNames) {
                    if (pair.getNameAsString().equals(attrName)) {
                        return pair.getValue().toString();
                    }
                }
            }
        }

        return null;
    }

    /**
     * Clean topic string from annotations
     */
    private String cleanTopicString(String topic) {
        if (topic == null) return null;

        // Remove surrounding quotes
        topic = topic.replace("\"", "");

        // Handle array notation {topic1, topic2}
        if (topic.startsWith("{") && topic.endsWith("}")) {
            topic = topic.substring(1, topic.length() - 1);
            // Take first topic if multiple
            if (topic.contains(",")) {
                topic = topic.split(",")[0].trim();
            }
        }

        return topic.trim();
    }

    /**
     * Detect MQ type from producer pattern
     */
    private MQEndpoint.MQType detectMQType(String pattern) {
        if (pattern.contains("kafka")) {
            return MQEndpoint.MQType.KAFKA;
        } else if (pattern.contains("rabbit")) {
            return MQEndpoint.MQType.RABBITMQ;
        } else if (pattern.contains("rocket")) {
            return MQEndpoint.MQType.ROCKETMQ;
        } else if (pattern.contains("jms")) {
            return MQEndpoint.MQType.JMS;
        }
        return MQEndpoint.MQType.KAFKA; // default
    }

    /**
     * Update global cache with scanned endpoints
     */
    private void updateGlobalCache(List<MQEndpoint> endpoints, GlobalAnalysisCache globalCache) {
        for (MQEndpoint endpoint : endpoints) {
            if (endpoint.getTopic() == null) continue;

            String topic = endpoint.getTopic();
            String methodSig = endpoint.getTargetMethod() != null
                    ? endpoint.getTargetMethod()
                    : endpoint.getSourceMethod();

            Map<String, Set<String>> targetMap = switch (endpoint.getMqType()) {
                case KAFKA -> globalCache.getKafkaEndpoints();
                case RABBITMQ -> globalCache.getRabbitEndpoints();
                case ROCKETMQ -> globalCache.getRocketMQEndpoints();
                case JMS -> globalCache.getJmsEndpoints();
            };

            targetMap.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet()).add(methodSig);
        }
    }
}