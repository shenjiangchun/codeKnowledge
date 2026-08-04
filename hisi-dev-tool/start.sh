#!/bin/bash

echo "========================================="
echo "Java LLM API Demo 启动脚本"
echo "========================================="

# 检查Java环境
if ! command -v java &> /dev/null; then
    echo "错误: 未找到Java环境，请先安装Java 17或更高版本"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
echo "当前Java版本: $JAVA_VERSION"

# 检查Maven环境
if ! command -v mvn &> /dev/null; then
    echo "错误: 未找到Maven环境，请先安装Maven 3.6或更高版本"
    exit 1
fi

MAVEN_VERSION=$(mvn -version | head -n 1)
echo "当前Maven版本: $MAVEN_VERSION"

echo ""
echo "开始编译项目..."
echo "========================================="

# 编译项目
mvn clean compile

if [ $? -ne 0 ]; then
    echo "错误: 项目编译失败，请检查代码和依赖"
    exit 1
fi

echo ""
echo "编译成功！开始运行项目..."
echo "========================================="

# 运行项目
mvn spring-boot:run

echo ""
echo "项目已停止"
echo "========================================="