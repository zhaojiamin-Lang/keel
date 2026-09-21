# Keel

> Spring Boot 3 Agent Runtime Starter —— 给业务服务一个默认不容易闯祸的 Agent 底盘。

业务服务加依赖后，按约定声明 Skill / 权限 / MCP，即可运行「可循环、可记忆、可检索、可审计、可评测」的智能体，而不是再手搓编排。

## 为什么需要 Keel

现有 Spring 业务系统加 AI 时，典型困境不是「没有模型」，而是：

- **能聊不能办**：一调用下单、关单、改状态，就重复提交、越权、编造业务 ID。
- **零件散、约定无**：Spring AI、LangChain4j、LangGraph、Langfuse 都要自己焊，每个项目焊法不同。
- **记忆混用**：当前对话、历史情景、长期偏好塞进同一个 Redis key，无法治理。
- **工具私有协议**：每接一个 GitLab / 工单 / OA 就重写鉴权、脱敏、重试。
- **上线无考卷**：Prompt / Skill 一改，不知道有没有更爱幻觉、更爱越权。

Keel 不解决：再做一个聊天 UI，或再做一个垂直业务系统（电商、工单中台等）。

Keel 要解决：给 Spring 业务一个**默认不容易闯祸的 Agent 底盘**。

## 30 分钟接入

### 1. 加依赖（5 分钟）

```xml
<dependency>
    <groupId>io.keel</groupId>
    <artifactId>keel-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Starter 已锁 Spring Boot 3.3+、Spring AI、LangChain4j、MCP SDK 版本（见 [keel-bom](keel-bom/pom.xml)），业务方无需再管三方版本。

### 2. 配 yml（5 分钟）

最小只读问答配置：

```yaml
keel:
  agent:
    mode: graph              # single-shot（默认，单次模型调用）或 graph（LangGraph 风格循环）
    max-steps: 5
    timeout: 30s
  # tools.allowed:           # 可选，配了之后启动期校验 skill 声明的工具必须在此白名单内
  retrieval:
    enabled: true            # 接 LangChain4j + 向量库（Milvus / PGVector）
    store:
      type: in-memory         # 开箱即用；生产用 pgvector 或 milvus
  checkpoint:
    enabled: false            # 示例用内存；生产改 true 并引入 keel-memory（详见「常用配置速查」）
  langfuse:
    enabled: false            # 按需开启 Trace
  principal:                  # 零配置可启动的默认身份；多租户生产请注册 PrincipalResolver bean
    tenant-id: tenant-demo
    subject-id: user-demo

spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
```

换 Redis 实例、换向量库、改重试次数等所有可配项，见下面的 [**常用配置速查**](#常用配置速查)。

> **启动自检**：默认 `off`——Redis / 向量库 / Langfuse 连不上时保持静默降级（可选依赖不强制阻塞启动）。业务期望「连不通就启动失败」时加一行：
>
> ```yaml
> keel:
>   startup:
>     connectivity-check: strict   # off（默认）/ warn（告警不阻断）/ strict（失败即拒绝启动）
> ```
>
> strict/warn 模式的失败报告会点名具体 bean 与配置键，例如
> `bean 'keelCheckpointPort'（RedisCheckpointPort）PING 失败 … 请检查 keel.checkpoint.host / keel.checkpoint.port`。
> 业务子系统可注册自己的 `StartupProbe` bean 纳入同一份报告。

### 3. 写 Skill（10 分钟）

在 `src/main/resources/skills/` 放一个 yaml，例如 `ticket-qa.yaml`：

```yaml
name: ticket-qa
description: 工单只读问答，答案必须带知识库引用
requireCitation: true
writable: false
tools: []                    # 只读 skill，不声明任何工具
```

字段含义见 [Skill 规范](docs/skill-spec.md)。

### 4. 跑起来（5 分钟）

业务代码只写一行：

```java
@Autowired
private KeelAgentClient keelClient;

AgentResult result = keelClient.chat("这单以前怎么处理的");
```

`keel-client` 把每个业务端点都要重写一遍、且最容易写错的四件事收口了：

| 收口的事 | 没有它时的样板（也是常见踩坑点） |
|----------|----------------------------------|
| 拼 `AgentRequest` | 手写 builder，字段漏一个就是运行期才发现的错 |
| 解析身份 | 直接 `new KeelPrincipal("tenant-A", "user-1", ...)` 硬编码，多租户下串号 |
| 生成幂等键 | 手拼 `"keel:" + tenant + ":" + sid`，格式错了很久才发现（Guard 对缺失或非法的幂等键 fail-closed） |
| 解析会话 ID | `"sess-" + System.currentTimeMillis()` 这类兜底——每次请求都是新会话，写确认必然失败 |

两个可选参数，其余全自动：

```java
keelClient.chat(input);              // skill 由内核按输入自动路由
keelClient.chat(input, "ticket-ops"); // 显式指定 skill
keelClient.confirm(actionId);         // 确认待执行写动作
```

`AgentResult` 含 `status / text / citations / pendingActions`。只读 skill 会直接返回带引用的答案；写工具默认转 `NEEDS_CONFIRM`，需用户确认后才执行。

**身份从哪来**：默认读 `keel.principal.*` 配置（保证零配置能启动，但多租户下会串号）。生产注册一个 bean 即可覆盖：

```java
@Bean
PrincipalResolver principalResolver() {
    return () -> {
        var req = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        return new KeelPrincipal(
                req.getHeader("X-Tenant-Id"),
                req.getHeader("X-User-Id"),
                List.of());
    };
}
```

**会话 ID 从哪来**：优先级为「显式入参 → `X-Session-Id` 请求头 → 按身份派生稳定值」。兜底值刻意不用随机数（随机数会让 checkpoint 永远命中不到，写确认静默失效），代价是同一身份的不同对话共享会话；需要并发多会话时请带 `X-Session-Id` 头。

> **想完全控制请求字段**（自定义 skill 路由、手工构造幂等键）时，直接注入底层 `KeelAgent` 即可——`KeelAgentClient` 是便利层，不是唯一入口：
>
> ```java
> @Autowired
> private KeelAgent keelAgent;
>
> AgentResult result = keelAgent.run(AgentRequest.builder()
>         .principal(new KeelPrincipal("tenant-A", "user-1", List.of("ISSUE-1")))
>         .skill("ticket-qa")
>         .input("这单以前怎么处理的")
>         .sessionId("sess-1")
>         .idempotencyKey("keel:tenant-A:sess-1")
>         .build());
> ```
>
> 不想要适配层时设 `keel.client.enabled=false`，行为与引入前完全一致。

> **观察每步状态**：注册一个 `StateObserver` bean 即可，starter 自动接入，无需手动装配 builder：
>
> ```java
> @Bean
> StateObserver auditObserver() {
>     return (node, snapshot) -> log.info("step={} iter={} citations={} pending={}",
>             node, snapshot.getIteration(), snapshot.getCitations().size(),
>             snapshot.getPendingAction() == null ? null : snapshot.getPendingAction().getTool());
> }
> ```
>
> 每个节点执行完回调一次，`snapshot` 为执行后的只读状态副本（请求、skill、迭代数、引用、草稿答案、Critic/Guard 判决、待确认动作、终局状态等）。
> 快照仅供观察，不要基于它做写决策；观察者抛出的异常被内核忽略，不影响请求结果。


### 5. 跑 Harness 回归（5 分钟）

在 `src/test/resources/eval/` 放 yaml 数据集：

```yaml
id: my-case
skill: ticket-qa
requireCitation: true
forbiddenSubstrings: []
allowedIssueIds: []
expectWrite: false
request:
  tenantId: tenant-A
  subjectId: user-1
  resourceIds: [ISSUE-1]
  input: 查询工单历史
  idempotencyKey: eval-1
```

字段含义见 [数据集规范](docs/eval-dataset-spec.md)。用 JUnit 跑：

```java
List<EvalCase> cases = new EvalCaseLoader().load("eval");
for (EvalCase evalCase : cases){
    AgentResult result = keelAgent.run(evalCase.toAgentRequest());
    EvalReport report = new KeelEvalChecker().check(evalCase, result);
    assertTrue(report.isPassed(), report.getFailedRules().toString());
}
```

内置 6 类断言：`leak` / `missing_citation` / `fabricated_id` / `unexpected_write` / `write_without_idempotency` / `exceeded_step_budget`。

### 端到端评测（真实 Agent 跑数）

上面那段是**接口用法**。真正回归 agent 行为，需要在示例里用真实 `KeelAgent` 跑数据集
——因为 `keel-harness` 自己的单元测试只验证 checker 规则实现，不覆盖“装配好的 agent + guard + graph”整链路。

示例 `examples/ticket-assist` 提供了一套可直接复用的模板：

| 文件 | 作用 |
|------|------|
| `src/test/resources/eval-e2e/*.yaml` | e2e 数据集，**单用例单文件**（`EvalCaseLoader` 按单个 YAML document 读取，多文档 `---` 会静默只取第一段） |
| `src/test/java/.../TicketAssistEvalE2ETest.java` | `@SpringBootTest` + `@ExtendWith(KeelEvalExtension.class)` + `@EvalDataset("eval-e2e")`，走 `controller.chat(...)` 真实全链路 |
| `src/test/java/.../TicketAssistEvalSentinelTest.java` | 负向哨兵：故意喂违规结果，证明评测**真会标红**，而不是恒绿 |

正向 e2e 与负向哨兵缺一不可：只有正向用例时，checker 写错、数据集加载不到、断言路径写歪，都会表现成“全绿”。哨兵用例把这几类静默失效变成显式失败。

跑法：

```bash
mvn -pl examples/ticket-assist -am test
```

示例自带 `ScriptedChatModel`，**默认零成本、确定性**，CI 无需模型 Key。

手动触发的 CI 模板见 [`.github/workflows/e2e-agent-eval.yml`](.github/workflows/e2e-agent-eval.yml)：`workflow_dispatch` 手动触发，`use_real_model` 输入项为 `true` 时注入 `OPENAI_API_KEY`（需已配置该 secret）改走真实模型，否则用脚本模型。

> 已知覆盖边界：`fabricated_id` 规则只匹配 `ISSUE-\d+`。示例若用 `T-001` 这类自有 ID 格式，该规则不会触发——哨兵测试里 `ticketStyleIdsAreNotCoveredByFabricatedIdRule` 显式记录了这一点。业务接自己的数据集时若 ID 格式不同，需要相应调整白名单/规则。

## 常用配置速查

按「我要换什么」组织。**所有项都有默认值，不配置即可运行**。

### 换模型

```yaml
spring:
  ai:
    openai:              # 换成 dashscope / azure-openai / ollama 等，由 Spring AI 装配
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
```

Keel 只依赖 `ChatModel` 抽象，不自研模型客户端。换厂商只需换 `spring.ai.*` 与对应 starter 依赖。

### 换向量库

```yaml
keel:
  retrieval:
    enabled: true
    max-results: 5              # 单轮检索最多取回片段数（topK）
    store:
      type: pgvector            # in-memory（默认开箱即用）/ pgvector / milvus
      dimension: 1536           # pgvector / milvus 必填，须与 embedding 模型输出一致
      host: pg.internal
      port: 5432                # pgvector 默认 5432 / milvus 默认 19530，不填按 type 兜底
      # ---- pgvector 专有 ----
      database: keel
      user: keel
      password: ${PG_PASSWORD}  # 密钥只走配置/环境变量
      table: keel_embeddings
      # ---- milvus 专有 ----
      # collection-name: keel_embeddings
      # uri: https://milvus.internal:19530   # 指定后覆盖 host/port
      # token: ${MILVUS_TOKEN}
```

不配 `type` 时不会创建默认 store，走「业务自备 `EmbeddingStore` bean」的老路径。

### 换 Redis 实例（checkpoint，生产必配）

写确认依赖 checkpoint 恢复待确认动作，**多实例部署必须用共享 Redis**。不引 `keel-memory` 却保持默认 `enabled=true` 时启动会失败——这是刻意的（内存实现多实例下会让「确认」静默无反应）。

```xml
<dependency>
    <groupId>io.keel</groupId>
    <artifactId>keel-memory</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
keel:
  checkpoint:
    enabled: true                 # 默认 true；不用 checkpoint 时显式设 false
    host: redis.internal
    port: 6379
    password: ${REDIS_PASSWORD}   # 无密码可不填
    database: 0                   # Redis DB index（0-15）
    ssl: false                    # 云 Redis / 跨公网访问置 true（rediss://）
    ttl: 30m                      # checkpoint 存活时间
    connect-timeout: 2s           # 建连超时，避免 Redis 不可达时启动挂住
    command-timeout: 3s           # 单条命令超时，避免单次 GET/SET 卡死整个请求
    max-retries: 3                # 断线自动重连次数，0 = 关闭
    retry-backoff: 200ms          # 重连退避基数
```

> `max-retries` 默认给 3 而不是跟随 Lettuce 的「不重连」：一次网络抖动会让后续所有 checkpoint 读写持续失败，表现为「点了确认没反应」。这是最难定位的一类故障。

### 换重试次数

两处独立的重试，别配错地方：

```yaml
keel:
  checkpoint:
    max-retries: 3                # Redis 断线重连（见上）
  tools:
    mcp:
      max-retries: 2              # MCP 传输类故障重试次数，0 = 不重试
      retry-backoff: 100ms
```

写工具的业务级去重不靠重试次数，而靠**幂等键**：`{prefix}:{tenantId}:{sessionId}`，同一会话共用一个键，重复提交由 server 端 dedupe。

### 换记忆存储

```yaml
keel:
  memory:
    enabled: true                 # 默认 false，显式开启后才注入 Loop
    max-fragments: 5              # 单次注入最大条数
    max-chars-per-fragment: 200   # 单条最大字符数
    l2:                           # 情景记忆
      type: mongo                 # in-memory（默认）/ mongo
      connection-string: mongodb://mongo.internal:27017
      database: keel
      collection: keel_episodic_memory
    l3:                           # 长期记忆
      type: jdbc                  # in-memory（默认）/ jdbc（PG）
      jdbc-url: jdbc:postgresql://pg.internal:5432/keel
      user: keel
      password: ${PG_PASSWORD}
```

### 改循环预算与超时

```yaml
keel:
  agent:
    mode: graph                   # single-shot（默认，单次模型调用）/ graph（循环 + 写确认）
    max-steps: 5                  # 单请求最大步数，超了返回 exceeded_step_budget
    timeout: 30s                  # 单请求总超时
    max-writes: 3                 # 单请求最多写动作数
```

### 收窄工具白名单

```yaml
keel:
  tools:
    allowed:                      # 配了之后启动期校验 skill 声明的工具必须在此列
      - ticket.query
      - ticket.create
    redact-mode: ALL              # TOKEN / MOBILE / SECRET / ALL（默认）/ NONE
```

白名单为空时跳过校验（opt-in），保证零配置项目仍能启动；一旦显式授权，未授权工具的 skill 在启动时即暴露，不拖到运行期。

### 接 MCP 工具

```yaml
keel:
  tools:
    mcp:
      enabled: true
      # ---- 单 server ----
      transport: sse              # sse / stdio；留空则不建默认 client（业务自备 McpSyncClient bean）
      base-url: https://mcp.internal
      sse-endpoint: /sse
      token: ${MCP_TOKEN}
      request-timeout: 10s
      initialization-timeout: 10s
      # ---- stdio 专有 ----
      # transport: stdio
      # command: npx
      # args: ["-y", "@modelcontextprotocol/server-gitlab"]
      # env:
      #   GITLAB_TOKEN: ${GITLAB_TOKEN}
      # ---- 多 server 路由 ----
      servers:
        gitlab:
          transport: sse
          base-url: https://mcp.internal
      credentials:                # per-call 凭证：serverName → token
        gitlab: ${GITLAB_TOKEN}
      require-principal-match: false   # true 时 principal.resourceIds 须含 serverName
```

### 开 Langfuse Trace

```yaml
keel:
  langfuse:
    enabled: true
    host: https://cloud.langfuse.com   # 自建填自己域名
    public-key: ${LANGFUSE_PUBLIC_KEY}
    secret-key: ${LANGFUSE_SECRET_KEY}
    redact: true                       # 默认开启脱敏
```

### 开启动自检

```yaml
keel:
  startup:
    connectivity-check: strict    # off（默认）/ warn（告警不阻断）/ strict（失败即拒绝启动）
    probe-timeout: 3s             # 单项探针超时（Redis PING / Langfuse HTTP 探活共用）
```

`strict` / `warn` 的失败报告会一次性汇总，点名具体 bean 与配置键，例如
`bean 'keelCheckpointPort'（RedisCheckpointPort）PING 失败 … 请检查 keel.checkpoint.host / keel.checkpoint.port`。
业务子系统可注册自己的 `StartupProbe` bean 纳入同一份报告。

### 配多租户身份

```yaml
keel:
  principal:
    tenant-id: tenant-demo        # 默认 default
    subject-id: user-demo         # 默认 anonymous
    resource-ids:                 # 资源级权限，参与 RAG / MCP 的 ACL 过滤
      - project-demo
```

> **本段对所有请求返回同一身份，多租户下会串号**（租户隔离失败属 P0 级问题）。生产请注册 `PrincipalResolver` bean（见「跑起来」一节），本段只用来保证零配置能启动。

### 适配层开关

```yaml
keel:
  client:
    enabled: true                 # false 时不创建 KeelAgentClient，业务退回自己拼 AgentRequest
    idempotency-key-prefix: keel  # 最终键为 {prefix}:{tenantId}:{sessionId}
```

多套 Keel 实例共用一个 Redis 时，用不同前缀避免互相 dedupe。


## 与 Spring AI / LangChain4j / LangGraph 的边界

Keel **不替代**任何一方，只做装配、约定、治理。

| 技术 | 解决什么 | Keel 的角色 |
|------|----------|-------------|
| **Spring AI** | 多模型接入（DashScope 等）、ChatClient | 只依赖 `ChatModel` 抽象，不自研模型客户端 |
| **LangChain4j** | 文档解析、切分、Embedding、检索器 | 加 ACL 过滤、citation 策略、与 Loop 的衔接；不自研向量库 |
| **LangGraph**（Java 侧图状态机） | Loop 循环、分支回退、checkpoint、可恢复 | keel-graph 提供图内核；不自研图执行器 |
| **MCP**（官方 SDK） | Tool 的标准接入协议 | keel-mcp 做只读适配 + 鉴权 / 脱敏 / 幂等治理；确认后的写（`NEEDS_CONFIRM` → 用户确认）经 `invokeConfirmedWrite` 放行并透传幂等键；不自研协议 |
| **Langfuse** | Trace、Span、Prompt/Skill 版本 | keel-langfuse 做自动上报；不自研观测 |

一次请求里各技术出场顺序：

```
Spring MVC（业务）
  → KeelAgent（装配层）
    → Memory L1/L2/L3 注入
    → LangGraph Loop
        → 需要知识：LangChain4j RAG（Milvus/PGVector）
        → 需要办事：Skill 允许的 Tool → MCP
        → Spring AI 生成计划/总结
        → Critic + Guard
    → Langfuse 上报
业务确认写入后 → MCP 写工具 → 业务 DB 真相源
CI → Harness
```

## 仓库模块

| 模块 | 职责 |
|------|------|
| [keel-spring-boot-starter](keel-spring-boot-starter) | 一行依赖、自动装配 |
| [keel-client](keel-client) | **接入适配层**：请求拼装 / 身份 / 会话 / 幂等键收口 |
| [keel-core](keel-core) | API / DTO（被 client 与其他内核模块依赖） |
| [keel-model-spring-ai](keel-model-spring-ai) | Spring AI 适配 + 单次 KeelAgent |
| [keel-rag-langchain4j](keel-rag-langchain4j) | LangChain4j 检索与 citation |
| [keel-graph](keel-graph) | LangGraph 风格 Loop / checkpoint |
| [keel-skill](keel-skill) | Skill 声明、加载、启动期校验 |
| [keel-mcp](keel-mcp) | MCP 工具接入与治理 |
| [keel-memory](keel-memory) | 三层记忆隔离（Redis / Mongo / PG） |
| [keel-guard](keel-guard) | 可控、准确、安全的代码规则 |
| [keel-langfuse](keel-langfuse) | 观测与版本 |
| [keel-harness](keel-harness) | 回归门禁（6 类断言） |
| [keel-bom](keel-bom) | 统一版本锁定 |
| [examples/ticket-assist](examples/ticket-assist) | 最小 Spring 工单服务示例 |

## 示例

```bash
cd examples/ticket-assist
mvn spring-boot:run

```

开箱即用：示例内置 `ScriptedChatModel`（脚本化模型），无需真实 API Key。详见 [examples/ticket-assist/README.md](examples/ticket-assist/README.md)。

## 验收看三件事

1. **好接入**：外部 Spring 项目只加 starter + 一段 yml + 一个 Skill 文件，能完成带 citation 的问答。
2. **默认安全**：同一写请求重放 10 次，业务侧只产生 1 次副作用；越权、编 ID、无引用、超步数全部被拦截。
3. **能回归**：Harness 固定集覆盖 6 类失败用例，框架能检出。

## 文档

- [Skill 规范](docs/skill-spec.md)
- [数据集规范](docs/eval-dataset-spec.md)
- [示例应用](examples/ticket-assist/README.md)

## 技术栈

Java 17+、Spring Boot 3.3+、Spring AI、LangChain4j、MCP 官方 SDK、Langfuse。原则：**零件用现成的，Keel 只做装配、约定、治理。**