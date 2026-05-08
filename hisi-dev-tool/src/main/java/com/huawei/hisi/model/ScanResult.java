package com.huawei.hisi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Scan result model representing the result of a scan operation.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanResult<T> {

    /**
     * Whether the scan was successful
     */
    private boolean success;

    /**
     * Error message if scan failed
     */
    private String errorMessage;

    /**
     * Number of items scanned
     */
    private int scannedCount;

    /**
     * Number of items found
     */
    private int foundCount;

    /**
     * List of found items
     */
    private List<T> items;

    /**
     * Scan duration in milliseconds
     */
    private long durationMs;

    /**
     * Scanner type that produced this result
     */
    private String scannerType;

    /**
     * Create a successful scan result
     */
    public static <T> ScanResult<T> success(List<T> items, String scannerType) {
        return ScanResult.<T>builder()
                .success(true)
                .items(items)
                .scannedCount(items != null ? items.size() : 0)
                .foundCount(items != null ? items.size() : 0)
                .scannerType(scannerType)
                .build();
    }

    /**
     * Create a failed scan result
     */
    public static <T> ScanResult<T> failure(String errorMessage, String scannerType) {
        return ScanResult.<T>builder()
                .success(false)
                .errorMessage(errorMessage)
                .scannerType(scannerType)
                .build();
    }
}