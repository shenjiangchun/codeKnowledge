/**
 * API Client for Spring Boot Backend
 * Provides HTTP communication with the HiSi Dev Tool backend services
 */
export interface ApiResponse<T> {
    success: boolean;
    data?: T;
    error?: string;
    message?: string;
}
export interface RequestOptions {
    timeout?: number;
    headers?: Record<string, string>;
}
export declare class ApiClient {
    private baseUrl;
    private defaultTimeout;
    constructor(baseUrl?: string);
    /**
     * Set the base URL for API requests
     */
    setBaseUrl(url: string): void;
    /**
     * Get the current base URL
     */
    getBaseUrl(): string;
    /**
     * Build URL with query parameters
     */
    private buildUrl;
    /**
     * Make HTTP request
     */
    private request;
    /**
     * GET request
     */
    get<T>(path: string, params?: Record<string, string>, options?: RequestOptions): Promise<T>;
    /**
     * POST request
     */
    post<T>(path: string, body: unknown, options?: RequestOptions): Promise<T>;
    /**
     * PUT request
     */
    put<T>(path: string, body: unknown, options?: RequestOptions): Promise<T>;
    /**
     * DELETE request
     */
    delete<T>(path: string, options?: RequestOptions): Promise<T>;
    /**
     * Check if the backend is healthy
     */
    healthCheck(): Promise<boolean>;
}
export declare function getApiClient(baseUrl?: string): ApiClient;
//# sourceMappingURL=apiClient.d.ts.map