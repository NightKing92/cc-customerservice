#!/bin/bash
set -e

echo "========================================="
echo "  银行智能客服 Agent - 部署脚本"
echo "========================================="

# 检查.env文件
if [ ! -f .env ]; then
    echo ""
    echo "[!] 未找到 .env 文件"
    echo "    请先创建 .env 文件并配置 DEEPSEEK_API_KEY:"
    echo ""
    echo "    cp .env.example .env"
    echo "    vim .env"
    echo ""
    exit 1
fi

# 检查Docker
if ! command -v docker &> /dev/null; then
    echo "[!] Docker未安装，正在安装..."
    curl -fsSL https://get.docker.com | sh
fi

if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
    echo "[!] Docker Compose未安装，正在安装..."
    apt-get update && apt-get install -y docker-compose-plugin
fi

echo ""
echo "[1/3] 停止旧容器..."
docker compose down 2>/dev/null || docker-compose down 2>/dev/null || true

echo ""
echo "[2/3] 构建并启动服务..."
docker compose up -d --build 2>/dev/null || docker-compose up -d --build

echo ""
echo "[3/3] 等待服务启动..."
sleep 5

# 健康检查
for i in {1..12}; do
    if curl -s http://localhost:8080/api/session/new > /dev/null 2>&1; then
        echo ""
        echo "========================================="
        echo "  ✅ 部署成功！"
        echo ""
        echo "  访问地址: http://$(hostname -I 2>/dev/null | awk '{print $1}' || echo 'YOUR_VPS_IP'):8080"
        echo "  本地测试: http://localhost:8080"
        echo ""
        echo "  API端点:"
        echo "    POST /api/session/new     - 创建新会话"
        echo "    POST /api/chat            - 发送消息"
        echo "    GET  /api/chat/stream     - 流式聊天(SSE)"
        echo "    GET  /api/session/{id}    - 查看会话状态"
        echo "========================================="
        exit 0
    fi
    echo "  等待中... ($i/12)"
    sleep 3
done

echo ""
echo "[!] 服务启动超时，请检查日志:"
echo "    docker compose logs -f"
exit 1
