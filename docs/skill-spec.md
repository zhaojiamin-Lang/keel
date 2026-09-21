# Skill 规范

> Keel 的「一项业务能力」声明规范。

Skill 把「一项业务能力」从代码里拆出来：用哪些 Tool、要不要引用、能不能写。Skill 是 classpath 资源（yaml），加载后不可变，启动期校验。

## 1. 文件位置

```
src/main/resources/skills/<name>.yaml
```

Starter 启动时由 `SkillLoader` 扫描 `classpath:skills/` 目录，加载所有 `.yaml` / `.yml` 文件。同名 skill 重复声明会启动失败（`Duplicate skill name`）。

## 2. 字段

| 字段 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `name` | string | 是 | — | Skill 唯一标识，请求时用 `AgentRequest.skill` 指定 |
| `description` | string | 否 | null | 业务能力描述，供路由 / 文档使用 |
| `requireCitation` | bool | 否 | false | true 时，无 `chunk_id` 的陈述不得进入最终结论 |
| `writable` | bool | 否 | false | true 时允许声明写工具；false 时即使声明了写工具，Guard 也会拒绝 |
| `tools` | list&lt;string&gt; | 否 | `[]` | 允许调用的工具名白名单；不在列表内的工具由 PlanNode 代码拦截 |

### 示例

#### 只读问答

```yaml
name: ticket-qa
description: 工单只读问答，答案必须带知识库引用
requireCitation: true
writable: false
tools: []
```

#### 可写操作

```yaml
name: ticket-ops
description: 工单操作 skill：查询、创建、改状态（可写，需用户确认）
requireCitation: false
writable: true
tools:
  - ticket.query
  - ticket.create
  - ticket.updateStatus
```

#### MCP 工具

```yaml
name: mcp-query
description: 通过只读 MCP 工具查询订单等信息，禁止任何写操作
requireCitation: false
writable: false
tools:
  - search_orders
```

## 3. 启动期校验

业务在 yml 配 `keel.tools.allowed` 后，Starter 会校验所有 Skill 声明的 `tools` 必须在该白名单内，否则启动失败（fail-closed）：

```yaml
keel:
  tools:
    allowed:
      - ticket.query
      - ticket.create
```

白名单为空时跳过校验（opt-in 模式，零配置项目仍能启动）。一旦业务显式授权，未授权工具的 Skill 在启动时即暴露，不拖到运行期。

运行期 PlanNode 仍保留防御纵深：模型请求调用的工具不在 Skill 的 `tools` 列表内，直接拒绝（`TOOL_NOT_PERMITTED`），不回流给模型改写。

## 4. 写工具的运行期保护

即使 `writable: true` 且 `tools` 含写工具，写工具也不会自动执行：

1. PlanNode 检测到写工具 → 转为 `PendingAction`，不执行
2. Guard 放行后返回 `NEEDS_CONFIRM`，待用户确认
3. 用户带 `confirmedActionId` 再次请求 → 从 checkpoint 恢复 → 执行写工具
4. PendingAction 携带请求级 `idempotencyKey`（透传自 `AgentRequest`），Harness 据此判定「写操作无幂等」是否成立

## 5. 路由

**现状**：请求必须显式指定 `AgentRequest.skill`，或回退默认 `chat` skill。`SkillRegistry` 只是 name→定义查表，无自动路由逻辑。

**规划**：运行时按用户输入 + 上下文选择 Skill；低置信走默认只读问答 Skill。待实现。

## 6. 版本

**现状**：`SkillDefinition` 无 `version` 字段。

**规划**：Skill 带版本号，与 Langfuse Prompt 版本关联。待实现。

## 7. 加载器行为

| 场景 | 行为 |
|------|------|
| 目录不存在 | 启动失败：`No classpath directory found: skills` |
| 目录存在但无 yaml | 启动失败：`No skill yaml files found` |
| 同名 skill 重复 | 启动失败：`Duplicate skill name: <name>` |
| yaml 缺 `name` | 启动失败：`Missing non-blank field: name` |

## 8. Skill 与图节点的协作

```
PlanNode   → 读 skill.tools 判定工具是否允许
           → writable=false 的 skill 出现写动作 → Guard 拒绝
ToolNode   → 写工具转 PendingAction，不执行
GuardNode  → skill 不可写却出现待确认写动作 → REJECTED（WRITE_FORBIDDEN_BY_SKILL）
CriticNode → requireCitation=true 且零召回 → 降级 UNCERTAIN（MISSING_CITATION）
```

## 9. 设计约束

- Skill 是**声明**，不是代码。业务能力边界（用哪些工具、能不能写、要不要引用）写在 yaml 里，不写在 Java 或 Prompt 里。
- Guard 规则用代码实现，不靠模型自觉。写身份判定、引用硬规则、工具白名单全部代码落地。
- Skill 不可热更新。改 Skill 需重启服务，配合 Harness 回归。