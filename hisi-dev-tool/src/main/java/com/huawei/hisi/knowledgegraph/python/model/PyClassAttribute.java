package com.huawei.hisi.knowledgegraph.python.model;

import lombok.Builder;
import lombok.Value;

/**
 * Represents a class-level attribute or annotated assignment in a Python class.
 * Covers Pydantic fields ({@code name: str}), Django model fields
 * ({@code name = models.CharField(...)}), and dataclass attributes.
 */
@Value
@Builder
public class PyClassAttribute {
    String name;
    String typeAnnotation;
    String defaultValue;
}
