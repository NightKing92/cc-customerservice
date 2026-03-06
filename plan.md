🤖 架构代码开发需求：工小智 (Gong Xiao Zhi) U型受控智能体系统改造
1. 项目背景与总体目标 (Context & Objective)
你现在是一位首席 Java 架构师。我们需要对现有的银行智能客服后端项目进行重构，将其打造成一个标准的 “U型受控自主智能体架构”。

核心流转 (U-Shape)： 客户端 -> Java 网关拦截 -> (调用本地 RAG 获取 Top-K 意图) -> 请求 Dify API -> Dify 推理并调用外部工具 (Skill/MCP) -> Java 返回执行结果 -> Dify 生成最终话术 -> Java 返回客户端。

零信任原则： Dify 是负责思考的“不可信外部大脑”。所有风控拦截、会话保持、Token 鉴权必须在 Java 端闭环完成。

2. 现有代码资产与重构目标 (Existing Assets & Refactoring Scope)
请在提供代码实现时，兼顾以下现有资产的融合：

基础工程复用： 我们将复用并重构现有的 Java Spring Boot 项目，工程名为 bank-cs-agent。你需要在这个工程基础上，增加 API Gateway 和 MCP Server 的核心能力。

本地 RAG 融合： 我们在本地已经有一个 RAG 的 Demo 跑通了，代码路径位于 /Users/liuchen/MyProject/aistation/demo/rag，可以纳入到本项目并进行调整复用，请预留出通过 HTTP 或 JNI 调用该 RAG 服务的接口逻辑，以便在请求 Dify 前，先获取“Top-K 候选意图”。

3. 核心开发任务拆解 (Core Tasks)
任务 A：统一流量网关与 RAG 前置 (Java API Gateway)
接口规范： 提供 POST /api/v1/chat/completions 接口供前端调用。

RAG 组装逻辑： 接收用户输入后，先调用本地 RAG 服务（路径参考上文）获取 Top 5 相关意图。

Dify 转发： 将 Top 5 intents 和 user_query 封装进 JSON，通过 RestTemplate/WebClient 调用 Dify 的开放 API，并处理流式/阻塞响应。

任务 B：标准 MCP Server 提供端 (Java MCP Server @ bank-cs-agent)
协议实现： 在 bank-cs-agent 中开辟一个标准的 MCP 调度端点（如 POST /mcp/execute）。

全局安全切面： 编写 AOP 拦截器，校验所有的 MCP 工具调用。当触发风控时，绝对禁止抛出 HTTP 500 异常，必须返回 HTTP 200 并在 JSON 中包含 {"status": "BLOCKED_BY_RISK", "observation": "具体的拦截话术"}，强制 LLM 读取拦截信息并重新规划。

4. 重点业务场景与 MCP/Skill 设计 (Interesting Scenarios)
为了充分发挥大模型的理解力与现有的基建，我们需要你设计并实现以下几个极具代表性的场景工具接口。请提供这些 MCP Tool 在 Java 端的代码实现模板。

场景 1：精准陪伴与数据洞察 (VPS 内部 MCP 调用)
业务描述： 客户问“我上个月怎么花了这么多钱？”

需要你实现的 MCP Tool： mcp_analyze_spending_habit(String userId, String month)。

执行逻辑： Java 端模拟查询核心账务库，聚合出餐饮、娱乐、交通等消费占比，并返回结构化数据给 Dify。Dify 的大模型会根据这些枯燥的数据，生成带有情绪价值的理财建议。

场景 2：外部实时信息与内部风控的碰撞 (Dify Native Skill + VPS MCP 联动)
业务描述： 客户问“今天黄金大涨，我账户里的钱够买 100 克吗？现在买合适吗？”

流转设计 (无需你写 Dify 侧代码，但需理解流转)： Dify 会先调用其原生的 Google Search Skill 查出今日实时金价，然后 Dify 决定调用我们 VPS 上的 MCP。

需要你实现的 MCP Tool： mcp_check_wealth_purchasing_power(String userId, BigDecimal requiredAmount, String riskType)。

执行逻辑： Java 端不仅要查客户余额够不够买 100 克黄金，还要查该客户的风险测评是否过期。如果过期，返回 observation：“余额充足，但客户风险测评已过期，请引导客户重新测评，当前禁止交易。”

场景 3：物理世界的副作用 (高危操作与人工介入)
业务描述： 客户要求开具资产证明并发送到自己的邮箱。

需要你实现的 MCP Tool： mcp_generate_and_email_asset_proof(String userId, String emailAddress)。

执行逻辑： 涉及文件生成和外部发送。Java 拦截器需模拟执行“敏感操作多因子认证”。如果检测到客户未在 App 内人脸识别，拒绝发送，返回 observation 让 LLM 引导客户去 App 扫脸。

5. 输出要求 (Deliverables)
请一次性输出以下内容：

网关层整合 RAG 调用的 Service 类核心逻辑。

基于 Spring AOP 的 MCP 安全拦截器代码。

场景 1 和场景 2 的 MCP Tool 接口及其对应的 DTO 定义。

请保持代码符合阿里巴巴 Java 开发规约，具备极强的企业级可读性。