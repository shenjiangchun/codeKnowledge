#!/bin/bash
# 直接插入测试数据到 SQLite 数据库
# 使用方法: ./insert-test-data.sh

DB_PATH="$HOME/.hisi-devtool/devtool.db"

echo "=== 日志分析测试数据插入 ==="
echo "数据库路径: $DB_PATH"

# 检查数据库是否存在
if [ ! -f "$DB_PATH" ]; then
    echo "警告: 数据库不存在，将创建新数据库"
fi

# 确保 log_analysis_report 表存在
echo ""
echo "Step 1: 确保表结构存在..."
sqlite3 "$DB_PATH" << 'EOSQL'
CREATE TABLE IF NOT EXISTS log_analysis_report (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    report_id BIGINT UNIQUE NOT NULL,
    report_no VARCHAR(50),
    user_id VARCHAR(50),
    query_params TEXT,
    log_message TEXT,
    log_stack_trace TEXT,
    filtered_stack_trace TEXT,
    error_type VARCHAR(100),
    trace_id VARCHAR(100),
    service_name VARCHAR(100),
    log_summary TEXT,
    status VARCHAR(20) DEFAULT 'pending',
    error_fingerprint VARCHAR(100),
    embedding_id VARCHAR(100),
    similarity_threshold DOUBLE,
    analysis_status VARCHAR(20),
    occurrence_count INTEGER DEFAULT 1,
    root_cause_text TEXT,
    fix_suggestion_text TEXT,
    created_at BIGINT,
    updated_at BIGINT
);
EOSQL

echo "表结构检查完成"

# Step 2: 插入测试数据
echo ""
echo "Step 2: 插入测试数据..."

NOW=$(date +%s)

# 测试数据1: NullPointerException (已完成状态)
sqlite3 "$DB_PATH" << EOSQL
INSERT OR REPLACE INTO log_analysis_report (
    report_id, report_no, user_id, log_message, log_stack_trace,
    error_type, trace_id, service_name, status, analysis_status,
    root_cause_text, fix_suggestion_text, occurrence_count, created_at, updated_at
) VALUES (
    1001, 'RPT-001', 'sys_admin',
    'ERROR: NullPointerException in UserService.findById',
    'java.lang.NullPointerException: Cannot invoke method on null object
	at com.example.user.UserService.findById(UserService.java:45)
	at com.example.user.UserController.getUser(UserController.java:23)
	at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:897)',
    'NullPointerException', 'trace-npe-001', 'user-service',
    'completed', 'completed',
    '根因分析: UserService.findById() 方法在第45行调用了null对象的方法。问题在于该方法没有对传入的userId参数或查询返回的user对象进行null检查。

调用链分析:
1. UserController.getUser() 接收请求调用 UserService.findById()
2. UserService.findById() 直接操作数据库查询返回的对象
3. 当数据库查询返回null时，后续操作导致NPE

建议检查点:
- UserService.java:45 的代码逻辑
- 数据库查询结果是否可能为null',
    '修复建议:
1. 在 UserService.findById() 方法中添加null检查:
   ```java
   public User findById(Long id) {
       User user = userRepository.findById(id);
       if (user == null) {
           throw new UserNotFoundException(id);
       }
       return user;
   }
   ```

2. 或者使用 Optional 包装返回值:
   ```java
   public Optional<User> findById(Long id) {
       return Optional.ofNullable(userRepository.findById(id));
   }
   ```

3. 前置校验: 在 Controller 层检查id参数有效性',
    5, $NOW, $NOW
);
EOSQL
echo "  -> 测试数据1 (NullPointerException) 已插入"

# 测试数据2: SQLException (处理中状态)
sqlite3 "$DB_PATH" << EOSQL
INSERT OR REPLACE INTO log_analysis_report (
    report_id, report_no, user_id, log_message, log_stack_trace,
    error_type, trace_id, service_name, status, analysis_status,
    root_cause_text, fix_suggestion_text, occurrence_count, created_at, updated_at
) VALUES (
    1002, 'RPT-002', 'sys_admin',
    'ERROR: Database connection failed',
    'java.sql.SQLException: Connection refused
	at com.mysql.cj.jdbc.ConnectionImpl.connect(ConnectionImpl.java:456)
	at com.example.database.ConnectionPool.getConnection(ConnectionPool.java:89)
	at com.example.order.OrderRepository.findAll(OrderRepository.java:34)',
    'SQLException', 'trace-sql-002', 'order-service',
    'completed', 'completed',
    '根因分析: 数据库连接被拒绝。可能原因:
1. 数据库服务未启动
2. 网络配置问题，无法访问数据库主机
3. 数据库连接池耗尽
4. 认证失败导致连接被拒绝

错误发生在 ConnectionImpl.connect() 层，说明是在建立TCP连接阶段失败，而非认证阶段。',
    '修复建议:
1. 检查数据库服务状态:
   - MySQL: systemctl status mysql
   - 确保数据库进程正在运行

2. 检查网络连通性:
   ```bash
   ping <database_host>
   telnet <database_host> 3306
   ```

3. 检查连接池配置:
   - 最大连接数是否设置合理
   - 连接超时时间是否过长

4. 检查数据库认证:
   - 用户名密码是否正确
   - 用户是否有远程访问权限',
    3, $NOW, $NOW
);
EOSQL
echo "  -> 测试数据2 (SQLException) 已插入"

# 测试数据3: OutOfMemoryError (已完成)
sqlite3 "$DB_PATH" << EOSQL
INSERT OR REPLACE INTO log_analysis_report (
    report_id, report_no, user_id, log_message, log_stack_trace,
    error_type, trace_id, service_name, status, analysis_status,
    root_cause_text, fix_suggestion_text, occurrence_count, created_at, updated_at
) VALUES (
    1003, 'RPT-003', 'sys_admin',
    'ERROR: Java heap space exhausted',
    'java.lang.OutOfMemoryError: Java heap space
	at com.example.cache.LargeDataCache.load(LargeDataCache.java:78)
	at com.example.cache.CacheManager.init(CacheManager.java:45)
	at com.example.app.ApplicationRunner.run(ApplicationRunner.java:23)',
    'OutOfMemoryError', 'trace-oom-003', 'cache-service',
    'completed', 'completed',
    '根因分析: JVM堆内存耗尽。问题发生在LargeDataCache.load()方法，该方法一次性加载大量数据到内存中。

内存分析:
- 大对象直接分配在堆上
- 缓存数据未分批加载
- 可能存在内存泄漏，缓存未正确清理',
    '修复建议:
1. 增加JVM堆内存:
   ```bash
   java -Xmx4g -Xms2g -jar app.jar
   ```

2. 优化缓存加载策略:
   - 分批加载大数据集
   - 使用懒加载替代全量加载
   - 实现缓存过期清理机制

3. 使用更高效的缓存方案:
   - 考虑使用Redis等外部缓存
   - 或使用堆外内存(DirectByteBuffer)

4. 分析内存泄漏:
   ```bash
   jmap -histo:live <pid>
   ```',
    2, $NOW, $NOW
);
EOSQL
echo "  -> 测试数据3 (OutOfMemoryError) 已插入"

# 测试数据4: 待处理状态
sqlite3 "$DB_PATH" << EOSQL
INSERT OR REPLACE INTO log_analysis_report (
    report_id, report_no, user_id, log_message, log_stack_trace,
    error_type, trace_id, service_name, status, analysis_status,
    occurrence_count, created_at, updated_at
) VALUES (
    1004, 'RPT-004', 'sys_admin',
    'WARN: Request timeout after 30 seconds',
    'java.util.concurrent.TimeoutException
	at java.util.concurrent.FutureTask.get(FutureTask.java:204)
	at com.example.http.HttpClient.execute(HttpClient.java:123)
	at com.example.external.ExternalApiService.call(ExternalApiService.java:67)',
    'TimeoutException', 'trace-timeout-004', 'gateway-service',
    'pending', 'pending',
    1, $NOW, $NOW
);
EOSQL
echo "  -> 测试数据4 (TimeoutException - pending) 已插入"

# 测试数据5: 处理中状态
sqlite3 "$DB_PATH" << EOSQL
INSERT OR REPLACE INTO log_analysis_report (
    report_id, report_no, user_id, log_message, log_stack_trace,
    error_type, trace_id, service_name, status, analysis_status,
    occurrence_count, created_at, updated_at
) VALUES (
    1005, 'RPT-005', 'sys_admin',
    'ERROR: Class not found during deserialization',
    'java.lang.ClassNotFoundException: com.example.dto.UserDTO
	at java.net.URLClassLoader.findClass(URLClassLoader.java:382)
	at com.example.serialization.JsonDeserializer.parse(JsonDeserializer.java:56)
	at com.example.api.ResponseParser.parse(ResponseParser.java:34)',
    'ClassNotFoundException', 'trace-cnfe-005', 'api-service',
    'processing', 'analyzing',
    1, $NOW, $NOW
);
EOSQL
echo "  -> 测试数据5 (ClassNotFoundException - processing) 已插入"

# Step 3: 查询验证
echo ""
echo "Step 3: 验证插入结果..."
sqlite3 "$DB_PATH" "SELECT report_id, error_type, status, analysis_status FROM log_analysis_report ORDER BY report_id;"

echo ""
echo "=== 测试数据插入完成 ==="
echo ""
echo "共插入 5 条测试数据:"
echo "  - 1001: NullPointerException (completed)"
echo "  - 1002: SQLException (completed)"
echo "  - 1003: OutOfMemoryError (completed)"
echo "  - 1004: TimeoutException (pending)"
echo "  - 1005: ClassNotFoundException (processing)"
echo ""
echo "请在前端「日志分析」->「分析报告」页面查看这些测试数据"