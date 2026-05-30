package com.huawei.hisi.neo4j.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("DataModel")
public class DataModelNode {

    @Id
    @Property("nodeId")
    private String nodeId;

    @Property("className")
    private String className;

    @Property("modelType")
    private String modelType;

    @Property("filePath")
    private String filePath;

    @Property("startLine")
    private Integer startLine;

    @Property("endLine")
    private Integer endLine;

    @Property("projectPath")
    private String projectPath;

    @Property("language")
    private String language;

    @Property("framework")
    private String framework;

    @Property("serviceName")
    private String serviceName;

    @Property("annotations")
    private List<String> annotations;

    @Property("fields")
    private List<String> fields;

    public static final String TYPE_JPA_ENTITY = "JPA_ENTITY";
    public static final String TYPE_LOMBOK_DATA = "LOMBOK_DATA";
    public static final String TYPE_PYDANTIC = "PYDANTIC";
    public static final String TYPE_DATACLASS = "DATACLASS";
    public static final String TYPE_DJANGO_MODEL = "DJANGO_MODEL";
    public static final String TYPE_SQLALCHEMY = "SQLALCHEMY";

    public static String generateNodeId(String projectPath, String className) {
        return projectPath + ":" + className;
    }
}
