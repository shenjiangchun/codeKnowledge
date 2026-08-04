package com.huawei.hisi.ram.kg.dto;

import java.util.List;

/** A MyBatis SQL mapping summary used by the deterministic validator. */
public record SqlMapping(String mapperInterface, String statementType, List<String> tableFields) {

    public SqlMapping {
        tableFields = tableFields == null ? List.of() : List.copyOf(tableFields);
    }
}
