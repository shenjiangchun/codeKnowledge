package com.huawei.hisi.ram.contract;

import java.util.List;

/**
 * Result of a JSON schema validation pass.
 *
 * @param passed         true when no violations and no missing required fields
 * @param missingFields  list of required fields that are missing from the payload
 * @param violations     list of other validation messages (type/enum/format/etc.)
 */
public record ValidationResult(
        boolean passed,
        List<String> missingFields,
        List<String> violations
) {
}
