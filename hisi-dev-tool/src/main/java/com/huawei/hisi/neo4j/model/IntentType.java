package com.huawei.hisi.neo4j.model;

/**
 * Intent type annotation for sub-queries in multi-channel search.
 *
 * <p>Each sub-query is tagged with an intent type that determines its
 * RRF weight in the final fusion. Intent types do <em>not</em> create
 * separate retrieval routes — all queries still go through the existing
 * {@code HybridSearchService} routing. The type only affects weight
 * and optional post-filter enhancements.</p>
 *
 * <p>Weight rationale (log-scaled via {@code w_eff = 1 + α·ln(w_raw/w_base)}):
 * <ul>
 *   <li>SCHEDULE / HTTP (raw 2.0) — high-precision structural matches</li>
 *   <li>SQL / EXCEPTION / LISTENER / CONFIG / AUTH / TRANSACTION (raw 1.8) — medium-precision</li>
 *   <li>GENERAL (raw 1.0) — baseline semantic search</li>
 *   <li>KEYWORD_SUPPLEMENT (raw 0.1) — last-resort keyword fallback</li>
 * </ul>
 */
public enum IntentType {

    /** Default: no special structural pattern detected. */
    GENERAL(1.0),

    /** Scheduled / periodic / cron tasks — e.g. @Scheduled methods. */
    SCHEDULE(2.0),

    /** HTTP endpoints / API controllers / REST requests. */
    HTTP(2.0),

    /** SQL operations / database queries / MyBatis mappers. */
    SQL(1.8),

    /** Exception handling / error catching / try-catch. */
    EXCEPTION(1.8),

    /** Event listeners / message consumers / callback triggers. */
    LISTENER(1.8),

    /** Configuration / properties / @Configuration classes. */
    CONFIG(1.8),

    /** Authentication / authorization / security. */
    AUTH(1.8),

    /** Transaction management / commit / rollback. */
    TRANSACTION(1.8),

    /** Keyword supplement — very low weight, only meaningful when vector search is empty. */
    KEYWORD_SUPPLEMENT(0.1);

    private final double defaultRawWeight;

    IntentType(double defaultRawWeight) {
        this.defaultRawWeight = defaultRawWeight;
    }

    /** Default raw weight before log-scaling. Configurable via application.yml. */
    public double getDefaultRawWeight() {
        return defaultRawWeight;
    }

    /** Annotations associated with this intent type (used for post-filter bonus). */
    public String[] associatedAnnotations() {
        return switch (this) {
            case SCHEDULE   -> new String[]{"@Scheduled"};
            case HTTP       -> new String[]{"@RequestMapping", "@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping", "@PatchMapping"};
            case LISTENER   -> new String[]{"@EventListener", "@Async", "@KafkaListener", "@RabbitListener", "@Subscribe"};
            case CONFIG     -> new String[]{"@Configuration", "@ConfigurationProperties", "@Value"};
            case AUTH       -> new String[]{"@PreAuthorize", "@Secured", "@RolesAllowed"};
            case TRANSACTION -> new String[]{"@Transactional"};
            default         -> new String[0];
        };
    }
}
