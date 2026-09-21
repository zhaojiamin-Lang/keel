# ticket-assist 示例

Keel 的最小落地示例：一个内存工单服务，演示 Keel 的两个核心场景——

- **只读**：Agent 通过 `ticket.query` 工具查询工单；
- **确认后写入**：Agent 提议建单 / 改状态，先返回「待确认动作」，用户确认后才真正写入；重复提交由幂等键保证只产生一次副作用。

内核不包含任何 Web 代码；HTTP 接口只出现在本示例里。

## 运行

```bash
cd examples/ticket-assist
mvn spring-boot:run
```

开箱即用：示例内置 `ScriptedChatModel`（脚本化模型），无需真实模型 API Key。
接入真实模型时，在 `application.yml` 配置 `spring.ai.*`，并删除 `ScriptedChatModelConfiguration` 中的桩实现（`@ConditionalOnMissingBean` 会自动让位）。

## 接口

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| POST | `/api/chat` | 发送用户问题，返回 `AgentResult`（可能含待确认动作） |
| POST | `/api/confirm` | 确认待执行的写动作，触发实际写入 |

### 请求头

| 头 | 说明 |
| -- | ---- |
| `X-Session-Id` | 关联多轮对话；写确认依赖 checkpoint 恢复，强烈建议带上 |
| `X-Tenant-Id` | 租户，缺省落回 `keel.principal.tenant-id` |
| `X-User-Id` | 主体，缺省落回 `keel.principal.subject-id` |
| `X-Resource-Ids` | 资源级权限，逗号分隔；缺省落回 `keel.principal.resource-ids` |

识别逻辑在 `TicketIdentityConfiguration`：它注册一个 `PrincipalResolver` bean 读这些头，
**缺任何头都会落回配置**，所以不带头也能跑通。`X-Session-Id` 缺失时会退化为按身份派生的
稳定会话 ID（不是随机数——随机数会让 checkpoint 永远命中不到）。

### 只读查询

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Session-Id: s1' \
  -d '{"input": "查询工单 T-001"}'
```

### 提议建单 → 确认写入

```bash
# 1) 提议：返回 NEEDS_CONFIRM + pendingActions[].actionId
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Session-Id: s2' \
  -d '{"input": "创建工单 登录失败"}'

# 2) 确认：用上一步返回的 actionId（须带同一 X-Session-Id，否则恢复不到待确认动作）
curl -X POST http://localhost:8080/api/confirm \
  -H 'Content-Type: application/json' -H 'X-Session-Id: s2' \
  -d '{"actionId": "<上一步的 actionId>"}'
```

## 接入有多薄

`TicketController` 的两个端点各只有一行 Keel 调用：

```java
@PostMapping("/chat")
public AgentResult chat(@RequestBody Map<String, String> body) {
    return keelClient.chat(body.get("input"), body.getOrDefault("skill", "ticket-ops"));
}

@PostMapping("/confirm")
public AgentResult confirm(@RequestBody Map<String, String> body) {
    return keelClient.confirm(body.get("actionId"));
}
```

身份解析、session 解析、幂等键生成、`AgentRequest` 拼装全部由 `keel-client` 收口。
改造前这里混着 30 多行 Keel 管道代码（含硬编码租户与手拼幂等键）——那是每个业务项目
都会重写一遍、且最容易写错 P0 安全语义的部分。业务要自己写的就是两处**业务语义**：

- `TicketIdentityConfiguration`：身份从哪来（这里读请求头）；
- `TicketToolPort`：工单工具是什么、哪个是写工具（`isWriteTool` 是写身份的唯一权威来源）。

## 目录

```
src/main/java/io/keel/examples/ticket/
  TicketAssistApplication.java          启动类
  TicketController.java                 /api/chat、/api/confirm（薄，各一行 Keel 调用）
  TicketIdentityConfiguration.java      PrincipalResolver：身份从请求头来，缺省落回配置
  InMemoryCheckpointConfiguration.java  内存 checkpoint 装配（示例开箱即用，生产换 RedisCheckpointPort）
  Ticket.java                           工单领域模型
  TicketService.java                    内存工单服务
  TicketToolPort.java                   把工单服务封装为图可调用的 ToolPort
  ScriptedChatModelConfiguration.java   脚本化模型桩
src/main/resources/
  application.yml                       graph 模式 + 工具白名单 + 身份兜底
  skills/ticket-ops.yaml                工单操作 Skill 声明
src/test/java/io/keel/examples/ticket/
  TicketAssistApplicationTest.java      端点级测试
  TicketAssistClientIntegrationTest.java 适配层集成：身份头 / 会话隔离 / 幂等键 / 重复确认
  TicketAssistEvalE2ETest.java          正向端到端评测（真实全链路）
  TicketAssistEvalSentinelTest.java     负向哨兵：证明评测真会标红
```

## 关于 checkpoint

示例是单实例、零外部依赖，因此 `application.yml` 里 **显式** 设了
`keel.checkpoint.enabled=false`，并自备内存实现（`InMemoryCheckpointConfiguration`）。

这不是可以照抄到生产的配置：内存 checkpoint 只在单实例内可见，多实例部署时用户点
「确认」的请求可能落到另一个实例，恢复不到待确认动作，表现为**确认点了没反应且没有报错**。
生产请引入 `io.keel:keel-memory` 并配 `keel.checkpoint.*`（含密码 / DB / SSL / 重试），
详见仓库根 [README 的「常用配置速查 → 换 Redis 实例」](../../README.md#常用配置速查)。

