#!/bin/bash
# KG Skills Kit Backend Fix Script
# Run this script after stopping the backend from IDEA or Task Manager

echo "=== KG Skills Kit Backend Fix ==="

echo ""
echo "Step 1: Checking if port 8080 is in use..."
if netstat -an | grep ":8080" | grep "LISTENING" > /dev/null 2>&1; then
    echo "ERROR: Port 8080 is still in use. Please stop the backend first:"
    echo "  - In IDEA: Click the red Stop button"
    echo "  - Or run: taskkill /F /PID 40568"
    exit 1
fi
echo "Port 8080 is free."

echo ""
echo "Step 2: Building new JAR package..."
mvn clean package -DskipTests -q
if [ $? -ne 0 ]; then
    echo "ERROR: Build failed. Check the error messages above."
    exit 1
fi
echo "Build successful."

echo ""
echo "Step 3: Starting backend..."
java -jar target/devTools-1.0.0.jar &
sleep 10

echo ""
echo "Step 4: Testing KG Skills Kit API..."
RESULT=$(curl -s http://localhost:8080/api/kg-skills-kit/list 2>/dev/null)
if echo "$RESULT" | grep '"code":200' > /dev/null; then
    echo "SUCCESS: KG Skills Kit API is working!"
    echo "$RESULT" | head -50
else
    echo "ERROR: API test failed. Response:"
    echo "$RESULT"
fi

echo ""
echo "=== Fix completed ==="