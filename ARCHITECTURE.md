# 银行智能客服系统 - 代码结构分析文档

## 1. 项目概述

这是一个**银行智能客服系统**，采用 Spring Boot + 前端 HTML/JS 架构，集成了 Dify (LLM工作流平台) 和 MCP (Model Context Protocol) 协议。

### 技术栈
- **后端**: Spring Boot 3.2.5 + Java 17 + Spring WebFlux
- **AI**: DeepSeek API + Dify Chatflow
- **前端**: 原生 HTML/CSS/JS + SSE 流式响应

---

## 2. 目录结构

```
cc-customerservice/
├── pom.xml                          # Maven 项目配置
├── docker-compose.yml               # Docker 容器编排
├── Dockerfile                       # Docker 镜像构建
├── deploy.sh                        # 部署脚本
├── dify-chatflow.yml                # Dify 工作流配置
├── plan.md                          # 项目规划文档
├── indextest.html                   # 测试页面
├── .env                             # 环境变量配置
└── src/main/
    ├── java/com/bank/cs/
    │   ├── Application.java         # Spring Boot 启动类
    │   ├── config/
    │   │   └── WebConfig.java       # Web 配置 (CORS等)
    │   ├── controller/
    │   │   ├── ChatController.java  # 旧版对话 API (DeepSeek直连)
    │   │   └── DifyController.java  # Dify 网关 API (SSE流式)
    │   ├── service/
    │   │   ├── DialogEngine.java    # 对话引擎核心
    │   │   ├── DifyGatewayService.java  # Dify SSE网关
    │   │   ├── DeepSeekService.java # DeepSeek API调用
    │   │   ├── IntentRegistry.java  # 意图注册中心
    │   │   └── SessionManager.java  # 会话管理
    │   ├── model/
    │   │   ├── ChatMessage.java     # 聊天消息模型
    │   │   ├── DialogFrame.java     # 对话帧(意图状态机)
    │   │   ├── LlmParsedResult.java  # LLM解析结果
    │   │   ├── SessionContext.java  # 会话上下文
    │   │   └── SlotDefinition.java  # 槽位定义
    │   └── mcp/
    │       ├── controller/
    │       │   ├── McpServerController.java  # MCP Server端点
    │       │   └── ToolController.java        # Dify工具端点
    │       ├── service/
    │       │   └── BankToolService.java       # 银行工具实现
    │       ├── annotation/
    │       │   └── RiskCheck.java              # 风控注解
    │       └── aspect/
    │           └── RiskControlAspect.java      # 风控AOP拦截
    └── resources/
        ├── application.yml       # Spring Boot配置
        └── static/
            └── index.html        # 前端页面
```

---

## 3. 核心功能模块

### 3.1 对话引擎 (DialogEngine)
- **文件**: `service/DialogEngine.java`
- **职责**: 意图识别、槽位收集、业务流程编排
- **功能**:
  - 意图识别分发
  - 槽位填充与校验
  - 意图跳转确认与多轮对话栈管理
  - 业务确认执行

### 3.2 Dify 网关 (DifyGatewayService)
- **文件**: `service/DifyGatewayService.java`
- **职责**: 与 Dify Chatflow 集成，代理 SSE 流式响应
- **功能**:
  - Dify API 调用
  - SSE 流式解析
  - 思维链 (`<think>`) 标签解析
  - 工具调用事件处理

### 3.3 LLM 服务 (DeepSeekService)
- **文件**: `service/DeepSeekService.java`
- **职责**: DeepSeek API 调用
- **功能**:
  - 意图识别 (JSON格式返回)
  - 流式问答生成

### 3.4 意图注册 (IntentRegistry)
- **文件**: `service/IntentRegistry.java`
- **已注册意图**:
  - `transfer` (转账) - 槽位: 收款人、金额
  - `credit_card` (信用卡办理) - 槽位: 卡片类型

### 3.5 会话管理 (SessionManager)
- **文件**: `service/SessionManager.java`
- **职责**: 会话创建/获取、定时清理过期会话 (30分钟超时)

### 3.6 MCP 工具服务 (BankToolService)
- **文件**: `mcp/service/BankToolService.java`
- **可用工具**:
  1. `analyze_spending_habit` - 消费习惯分析
  2. `check_wealth_purchasing_power` - 理财购买力评估
  3. `generate_and_email_asset_proof` - 资产证明邮件

### 3.7 风控模块
- **文件**: `mcp/aspect/RiskControlAspect.java`
- **职责**: AOP 拦截敏感工具调用

---

## 4. API 接口定义

### 4.1 Dify 网关接口
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/dify/stream?sessionId=&message=` | SSE 流式对话 |
| POST | `/api/dify/session/{sessionId}/clear` | 清除会话 |

### 4.2 MCP 协议接口
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/mcp` | MCP JSON-RPC 端点 |

### 4.3 Dify 工具接口
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/tools/analyze-spending-habit` | 消费习惯分析 |
| POST | `/api/tools/check-wealth-purchasing-power` | 理财购买力评估 |
| POST | `/api/tools/generate-asset-proof` | 资产证明邮件 |

---

## 5. 数据模型

### 5.1 ChatMessage
```java
enum Role { USER, ASSISTANT, SYSTEM }
- role: 消息角色
- content: 消息内容
- timestamp: 时间戳
```

### 5.2 DialogFrame
```java
enum State { COLLECTING, CONFIRMING, EXECUTING, SUSPENDED, DONE }
- intentId: 意图ID
- intentName: 意图名称
- slotSchema: 槽位定义
- collectedSlots: 已收集槽位
- state: 对话状态
```

### 5.3 SessionContext
- sessionId: 会话ID
- dialogStack: 对话栈 (支持多意图嵌套)
- chatHistory: 对话历史

### 5.4 LlmParsedResult
- intent: 识别到的意图 (transfer/credit_card/qa/none)
- slots: 提取的槽位
- qaAnswer: 直接回答 (QA意图时)

---

## 6. SSE 事件类型

| 事件类型 | 说明 |
|----------|------|
| `thinking` | LLM 思维链 |
| `tool_call` | 工具调用 |
| `tool_result` | 工具结果 |
| `text` | 最终回复 |
| `done` | 完成信号 |
| `error` | 错误信息 |

---

## 7. 配置文件

| 文件 | 作用 |
|------|------|
| `pom.xml` | Maven 依赖配置 |
| `application.yml` | Spring Boot 应用配置 |
| `dify-chatflow.yml` | Dify 工作流定义 |
| `.env` | 环境变量 (API Keys) |

---

*文档生成时间: 2026-03-08*
