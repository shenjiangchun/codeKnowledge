-- ============================================================
-- HiSi DevTool Database Initialization Script
-- Database: OpenGauss (PostgreSQL compatible)
-- Version: 1.0.0
-- ============================================================

-- Create schema if not exists
CREATE SCHEMA IF NOT EXISTS hiapm_test;

-- ============================================================
-- Utility Functions
-- ============================================================

-- Create trigger function for auto-updating updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- Table: method_call_graph5
-- Purpose: Store method call chain graph for URI analysis
-- ============================================================
CREATE TABLE IF NOT EXISTS hiapm_test.method_call_graph5 (
    id              SERIAL PRIMARY KEY,
    root_uri        VARCHAR(512) NOT NULL,
    parent_method   VARCHAR(2000) NOT NULL,
    package         VARCHAR(512) NOT NULL,
    method_body     VARCHAR(10000) NOT NULL,
    child_method    VARCHAR(2000) NOT NULL,
    depth           INTEGER NOT NULL,
    call_type       VARCHAR(20) DEFAULT 'DIRECT',
    project_dir     VARCHAR(512),
    created_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for method_call_graph5
CREATE INDEX IF NOT EXISTS idx_mcg_root_uri ON hiapm_test.method_call_graph5(root_uri);
CREATE INDEX IF NOT EXISTS idx_mcg_package ON hiapm_test.method_call_graph5(package);
CREATE INDEX IF NOT EXISTS idx_mcg_parent ON hiapm_test.method_call_graph5(parent_method);
CREATE INDEX IF NOT EXISTS idx_mcg_child ON hiapm_test.method_call_graph5(child_method);
CREATE INDEX IF NOT EXISTS idx_mcg_depth ON hiapm_test.method_call_graph5(depth);
CREATE INDEX IF NOT EXISTS idx_mcg_call_type ON hiapm_test.method_call_graph5(call_type);
CREATE INDEX IF NOT EXISTS idx_mcg_project_dir ON hiapm_test.method_call_graph5(project_dir);

COMMENT ON TABLE hiapm_test.method_call_graph5 IS 'Method call chain graph - stores URI to method call relationships';
COMMENT ON COLUMN hiapm_test.method_call_graph5.root_uri IS 'Entry URI (controller endpoint)';
COMMENT ON COLUMN hiapm_test.method_call_graph5.parent_method IS 'Parent method in call chain';
COMMENT ON COLUMN hiapm_test.method_call_graph5.child_method IS 'Child method called by parent';
COMMENT ON COLUMN hiapm_test.method_call_graph5.depth IS 'Depth in call tree (0 = entry point)';
COMMENT ON COLUMN hiapm_test.method_call_graph5.method_body IS 'Method body content for analysis';
COMMENT ON COLUMN hiapm_test.method_call_graph5.call_type IS 'Call type: DIRECT/MQ_SEND/MQ_RECEIVE/FEIGN/HTTP/MYBATIS/JPA/AOP';
COMMENT ON COLUMN hiapm_test.method_call_graph5.project_dir IS 'Project directory path for data isolation';

-- ============================================================
-- Table: log_analysis_report
-- Purpose: Store async log analysis task reports
-- ============================================================
CREATE TABLE IF NOT EXISTS log_analysis_report (
    report_id           BIGINT PRIMARY KEY,
    user_id             VARCHAR(64) DEFAULT 'sys_admin',
    status              VARCHAR(20) NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending', 'processing', 'completed', 'failed')),
    log_message         TEXT,
    log_stack_trace     TEXT,
    filtered_stack_trace TEXT,
    error_type          VARCHAR(100),
    error_summary       JSONB,
    root_cause          JSONB,
    fix_suggestions     JSONB,
    code_snippets       JSONB,
    trace_id            VARCHAR(128),
    service_name        VARCHAR(128),
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    error_message       TEXT
);

-- Indexes for log_analysis_report
CREATE INDEX IF NOT EXISTS idx_lar_user_status ON log_analysis_report(user_id, status);
CREATE INDEX IF NOT EXISTS idx_lar_created_at ON log_analysis_report(created_at);
CREATE INDEX IF NOT EXISTS idx_lar_status ON log_analysis_report(status);
CREATE INDEX IF NOT EXISTS idx_lar_trace_id ON log_analysis_report(trace_id);

-- Trigger for auto-updating updated_at
DROP TRIGGER IF EXISTS update_log_analysis_report_updated_at ON log_analysis_report;
CREATE TRIGGER update_log_analysis_report_updated_at
    BEFORE UPDATE ON log_analysis_report
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE log_analysis_report IS 'Log analysis report table - stores async log analysis task results';
COMMENT ON COLUMN log_analysis_report.report_id IS 'Snowflake-generated globally unique ID';
COMMENT ON COLUMN log_analysis_report.status IS 'Task status: pending/processing/completed/failed';

-- ============================================================
-- Table: log_cloud_query_cache
-- Purpose: Cache for log cloud query results
-- ============================================================
CREATE TABLE IF NOT EXISTS log_cloud_query_cache (
    id              BIGSERIAL PRIMARY KEY,
    query_key       VARCHAR(256) NOT NULL UNIQUE,
    log_data        JSONB NOT NULL,
    expire_at       TIMESTAMP NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for log_cloud_query_cache
CREATE INDEX IF NOT EXISTS idx_lcqc_expire_at ON log_cloud_query_cache(expire_at);
CREATE INDEX IF NOT EXISTS idx_lcqc_query_key ON log_cloud_query_cache(query_key);

COMMENT ON TABLE log_cloud_query_cache IS 'Log cloud query cache table';

-- ============================================================
-- Table: mq_call_bridge
-- Purpose: Store MQ producer-consumer relationships
-- ============================================================
CREATE TABLE IF NOT EXISTS mq_call_bridge (
    id              BIGSERIAL PRIMARY KEY,
    source_method   VARCHAR(500) NOT NULL,
    source_class    VARCHAR(500),
    topic           VARCHAR(256) NOT NULL,
    message_type    VARCHAR(50) DEFAULT 'KAFKA',
    target_method   VARCHAR(500),
    target_class    VARCHAR(500),
    consumer_group  VARCHAR(256),
    package         VARCHAR(256),
    project_dir     VARCHAR(512),
    metadata        JSONB,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for mq_call_bridge
CREATE INDEX IF NOT EXISTS idx_mq_topic ON mq_call_bridge(topic);
CREATE INDEX IF NOT EXISTS idx_mq_source_method ON mq_call_bridge(source_method);
CREATE INDEX IF NOT EXISTS idx_mq_target_method ON mq_call_bridge(target_method);
CREATE INDEX IF NOT EXISTS idx_mq_package ON mq_call_bridge(package);
CREATE INDEX IF NOT EXISTS idx_mq_project_dir ON mq_call_bridge(project_dir);

COMMENT ON TABLE mq_call_bridge IS 'MQ call bridge - stores producer-consumer relationships';
COMMENT ON COLUMN mq_call_bridge.message_type IS 'Message queue type: KAFKA/RABBITMQ/ROCKETMQ/JMS';
COMMENT ON COLUMN mq_call_bridge.project_dir IS 'Project directory path for data isolation';

-- ============================================================
-- Table: http_call_bridge
-- Purpose: Store HTTP client to server relationships
-- ============================================================
CREATE TABLE IF NOT EXISTS http_call_bridge (
    id              BIGSERIAL PRIMARY KEY,
    source_method   VARCHAR(500) NOT NULL,
    source_class    VARCHAR(500) NOT NULL,
    service_name    VARCHAR(256) NOT NULL,
    http_method     VARCHAR(10) NOT NULL,
    uri_pattern     VARCHAR(512) NOT NULL,
    target_method   VARCHAR(500),
    target_class    VARCHAR(500),
    package         VARCHAR(256),
    project_dir     VARCHAR(512),
    metadata        JSONB,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for http_call_bridge
CREATE INDEX IF NOT EXISTS idx_http_uri_pattern ON http_call_bridge(uri_pattern);
CREATE INDEX IF NOT EXISTS idx_http_service_name ON http_call_bridge(service_name);
CREATE INDEX IF NOT EXISTS idx_http_source_method ON http_call_bridge(source_method);
CREATE INDEX IF NOT EXISTS idx_http_package ON http_call_bridge(package);
CREATE INDEX IF NOT EXISTS idx_http_project_dir ON http_call_bridge(project_dir);

COMMENT ON TABLE http_call_bridge IS 'HTTP call bridge - stores Feign/HTTP client to server relationships';
COMMENT ON COLUMN http_call_bridge.project_dir IS 'Project directory path for data isolation';

-- ============================================================
-- Table: proxy_metadata
-- Purpose: Store proxy class metadata (MyBatis, JPA, AOP)
-- ============================================================
CREATE TABLE IF NOT EXISTS proxy_metadata (
    id                  BIGSERIAL PRIMARY KEY,
    interface_name      VARCHAR(500) NOT NULL,
    interface_type      VARCHAR(50) NOT NULL,
    implementation      VARCHAR(500),
    proxy_type          VARCHAR(50) NOT NULL,
    method_name         VARCHAR(256),
    method_signature    VARCHAR(1000),
    sql_statement       TEXT,
    entity_type         VARCHAR(500),
    package             VARCHAR(256),
    project_dir         VARCHAR(512),
    metadata            JSONB,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for proxy_metadata
CREATE INDEX IF NOT EXISTS idx_proxy_interface ON proxy_metadata(interface_name);
CREATE INDEX IF NOT EXISTS idx_proxy_type ON proxy_metadata(proxy_type);
CREATE INDEX IF NOT EXISTS idx_proxy_method ON proxy_metadata(method_name);
CREATE INDEX IF NOT EXISTS idx_proxy_package ON proxy_metadata(package);
CREATE INDEX IF NOT EXISTS idx_proxy_project_dir ON proxy_metadata(project_dir);

COMMENT ON TABLE proxy_metadata IS 'Proxy metadata - stores info about MyBatis Mappers, JPA Repositories, AOP Aspects';
COMMENT ON COLUMN proxy_metadata.interface_type IS 'Interface type: MYBATIS/JPA/AOP/JDK_PROXY/CGLIB';
COMMENT ON COLUMN proxy_metadata.proxy_type IS 'Proxy type: MYBATIS_MAPPER/JPA_REPOSITORY/ASPECT/JDK_DYNAMIC/CGLIB';
COMMENT ON COLUMN proxy_metadata.project_dir IS 'Project directory path for data isolation';

-- ============================================================
-- Table: service_topology
-- Purpose: Store service dependency topology
-- ============================================================
CREATE TABLE IF NOT EXISTS service_topology (
    id              BIGSERIAL PRIMARY KEY,
    source_service  VARCHAR(256) NOT NULL,
    target_service  VARCHAR(256) NOT NULL,
    call_type       VARCHAR(50) NOT NULL,
    endpoint        VARCHAR(512),
    package         VARCHAR(256),
    project_dir     VARCHAR(512),
    metadata        JSONB,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for service_topology
CREATE INDEX IF NOT EXISTS idx_topology_source ON service_topology(source_service);
CREATE INDEX IF NOT EXISTS idx_topology_target ON service_topology(target_service);
CREATE INDEX IF NOT EXISTS idx_topology_call_type ON service_topology(call_type);
CREATE INDEX IF NOT EXISTS idx_topology_package ON service_topology(package);
CREATE INDEX IF NOT EXISTS idx_topology_project_dir ON service_topology(project_dir);

-- Trigger for auto-updating updated_at
DROP TRIGGER IF EXISTS update_service_topology_updated_at ON service_topology;
CREATE TRIGGER update_service_topology_updated_at
    BEFORE UPDATE ON service_topology
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE service_topology IS 'Service topology - stores overall service dependency graph';
COMMENT ON COLUMN service_topology.call_type IS 'Call type: FEIGN/HTTP/MQ/GRPC';
COMMENT ON COLUMN service_topology.project_dir IS 'Project directory path for data isolation';

-- ============================================================
-- Table: app_config
-- Purpose: Store application runtime configuration
-- ============================================================
CREATE TABLE IF NOT EXISTS app_config (
    key             VARCHAR(100) PRIMARY KEY,
    value           VARCHAR(1000) NOT NULL,
    description     VARCHAR(500),
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(100) DEFAULT 'system'
);

-- Indexes for app_config
CREATE INDEX IF NOT EXISTS idx_app_config_key ON app_config(key);

COMMENT ON TABLE app_config IS 'Application configuration table - stores runtime configurable settings';
COMMENT ON COLUMN app_config.key IS 'Configuration key';
COMMENT ON COLUMN app_config.value IS 'Configuration value';
COMMENT ON COLUMN app_config.description IS 'Configuration description';

-- ============================================================
-- Initial Data (Optional)
-- ============================================================
-- Insert default PROJECT_DIR config
INSERT INTO app_config (key, value, description)
VALUES ('PROJECT_DIR', '', '项目代码存放目录')
ON CONFLICT (key) DO NOTHING;

-- ============================================================
-- End of Initialization Script
-- ============================================================