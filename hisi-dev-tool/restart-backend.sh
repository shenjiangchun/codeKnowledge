#!/bin/bash
# 后端重启脚本 - 用于测试日志分析功能
# 使用方法: 在停止 IDEA 后端后执行此脚本

echo "=== 后端重启脚本 ==="

# Step 1: 检查端口是否已释放
echo ""
echo "Step 1: 检查端口 8080..."
if netstat -an | grep ":8080" | grep "LISTENING" > /dev/null 2>&1; then
    echo "警告: 端口 8080 仍在使用"
    echo "请在 IDEA 中点击红色 Stop 按钮，或手动执行: taskkill /F /PID 40568"
    echo "然后重新运行此脚本"
    exit 1
fi
echo "端口 8080 已释放"

# Step 2: 构建新 JAR
echo ""
echo "Step 2: 构建新 JAR 包..."
mvn clean package -DskipTests -q
if [ $? -ne 0 ]; then
    echo "错误: 构建失败"
    exit 1
fi
echo "构建成功"

# Step 3: 启动后端
echo ""
echo "Step 3: 启动后端..."
java -jar target/devTools-1.0.0.jar &
sleep 15

# Step 4: 检查启动状态
echo ""
echo "Step 4: 检查启动状态..."
HEALTH=$(curl -s http://localhost:8080/actuator/health 2>/dev/null)
if echo "$HEALTH" | grep "UP" > /dev/null; then
    echo "后端启动成功: $HEALTH"
else
    echo "警告: 后端可能未完全启动"
fi

# Step 5: 登录获取 token
echo ""
echo "Step 5: 登录获取 JWT token..."
LOGIN_RESP=$(curl -s -X POST http://localhost:8080/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username": "root", "password": "123456"}' 2>/dev/null)

TOKEN=$(echo "$LOGIN_RESP" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [ -z "$TOKEN" ]; then
    echo "登录失败"
    echo "$LOGIN_RESP"
    exit 1
fi
echo "登录成功，Token: ${TOKEN:0:20}..."

# Step 6: 提交测试日志分析请求
echo ""
echo "Step 6: 提交测试日志分析请求..."

# 测试用例1: NullPointerException
echo "  -> NullPointerException 测试..."
curl -s -X POST http://localhost:8080/api/log/analyze \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{
        "message": "ERROR: NullPointerException in UserService.findById",
        "stackTrace": "java.lang.NullPointerException: Cannot invoke method on null object\n\tat com.example.user.UserService.findById(UserService.java:45)\n\tat com.example.user.UserController.getUser(UserController.java:23)\n\tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:897)",
        "errorType": "NullPointerException",
        "serviceName": "user-service",
        "traceId": "trace-npe-001"
    }' 2>/dev/null
echo ""

# 测试用例2: SQLException
echo "  -> SQLException 测试..."
curl -s -X POST http://localhost:8080/api/log/analyze \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{
        "message": "ERROR: Database connection failed",
        "stackTrace": "java.sql.SQLException: Connection refused\n\tat com.mysql.cj.jdbc.ConnectionImpl.connect(ConnectionImpl.java:456)\n\tat com.example.database.ConnectionPool.getConnection(ConnectionPool.java:89)\n\tat com.example.order.OrderRepository.findAll(OrderRepository.java:34)",
        "errorType": "SQLException",
        "serviceName": "order-service",
        "traceId": "trace-sql-002"
    }' 2>/dev/null
echo ""

# 测试用例3: OutOfMemoryError
echo "  -> OutOfMemoryError 测试..."
curl -s -X POST http://localhost:8080/api/log/analyze \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{
        "message": "ERROR: Java heap space exhausted",
        "stackTrace": "java.lang.OutOfMemoryError: Java heap space\n\tat com.example.cache.LargeDataCache.load(LargeDataCache.java:78)\n\tat com.example.cache.CacheManager.init(CacheManager.java:45)\n\tat com.example.app.ApplicationRunner.run(ApplicationRunner.java:23)",
        "errorType": "OutOfMemoryError",
        "serviceName": "cache-service",
        "traceId": "trace-oom-003"
    }' 2>/dev/null
echo ""

# 测试用例4: ClassNotFoundException
echo "  -> ClassNotFoundException 测试..."
curl -s -X POST http://localhost:8080/api/log/analyze \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{
        "message": "ERROR: Class not found during deserialization",
        "stackTrace": "java.lang.ClassNotFoundException: com.example.dto.UserDTO\n\tat java.net.URLClassLoader.findClass(URLClassLoader.java:382)\n\tat com.example.serialization.JsonDeserializer.parse(JsonDeserializer.java:56)\n\tat com.example.api.ResponseParser.parse(ResponseParser.java:34)",
        "errorType": "ClassNotFoundException",
        "serviceName": "api-service",
        "traceId": "trace-cnfe-004"
    }' 2>/dev/null
echo ""

# 测试用例5: TimeoutException
echo "  -> TimeoutException 测试..."
curl -s -X POST http://localhost:8080/api/log/analyze \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{
        "message": "WARN: Request timeout after 30 seconds",
        "stackTrace": "java.util.concurrent.TimeoutException\n\tat java.util.concurrent.FutureTask.get(FutureTask.java:204)\n\tat com.example.http.HttpClient.execute(HttpClient.java:123)\n\tat com.example.external.ExternalApiService.call(ExternalApiService.java:67)",
        "errorType": "TimeoutException",
        "serviceName": "gateway-service",
        "traceId": "trace-timeout-005"
    }' 2>/dev/null
echo ""

# Step 7: 等待分析完成
echo ""
echo "Step 7: 等待分析完成 (约 30 秒)..."
sleep 30

# Step 8: 查看报告列表
echo ""
echo "Step 8: 查看报告列表..."
curl -s http://localhost:8080/api/log/reports \
    -H "Authorization: Bearer $TOKEN" 2>/dev/null | head -100
echo ""

echo ""
echo "=== 重启和测试完成 ==="
echo ""
echo "提示:"
echo "  1. 打开前端页面 http://localhost:5173"
echo "  2. 点击左侧菜单「日志分析」"
echo "  3. 点击「分析报告」标签页查看测试报告"
echo "  4. 对于已完成的报告，点击「查看报告」查看根因分析"