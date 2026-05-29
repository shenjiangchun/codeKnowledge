package com.huawei.hisi.scanner;

import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.model.ScanResult;

import java.nio.file.Path;
import java.util.List;

/**
 * Base interface for endpoint scanners.
 * All scanners (MQ, Feign, HTTP, Proxy) implement this interface.
 *
 * @param <T> the type of endpoint being scanned
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
public interface EndpointScanner<T> {

    /**
     * Get the scanner name/type
     * @return scanner name
     */
    String getScannerName();

    /**
     * Scan a single source file and extract endpoints
     *
     * @param filePath    the source file path
     * @param globalCache the global analysis cache
     * @return scan result containing found endpoints
     */
    ScanResult<T> scanFile(Path filePath, GlobalAnalysisCache globalCache);

    /**
     * Scan multiple source files and extract endpoints
     *
     * @param filePaths   list of source file paths
     * @param globalCache the global analysis cache
     * @return scan result containing all found endpoints
     */
    ScanResult<T> scanFiles(List<Path> filePaths, GlobalAnalysisCache globalCache);

    /**
     * Check if this scanner can handle the given file
     *
     * @param filePath the source file path
     * @return true if this scanner can handle the file
     */
    boolean canScan(Path filePath);

    /**
     * Get the supported annotation names for this scanner
     * Useful for pre-filtering files
     *
     * @return set of annotation names to look for
     */
    java.util.Set<String> getSupportedAnnotations();

    /**
     * Scan a single file by path string (convenience overload for incremental refresh).
     * Delegates to {@link #scanFile(Path, GlobalAnalysisCache)} with a fresh cache.
     *
     * @param filePath the source file path as a string
     * @return scan result containing found endpoints
     */
    default ScanResult<T> scanFile(String filePath) {
        return scanFile(Path.of(filePath), new GlobalAnalysisCache());
    }

    /**
     * Initialize the scanner before scanning
     *
     * @param globalCache the global analysis cache
     */
    default void initialize(GlobalAnalysisCache globalCache) {
        // Default: no initialization needed
    }

    /**
     * Clean up after scanning
     */
    default void cleanup() {
        // Default: no cleanup needed
    }
}