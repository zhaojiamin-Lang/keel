package io.keel.examples.ticket;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.keel.client.KeelAgentClient;
import io.keel.core.AgentResult;

/**
 * 示例对外接口（FR-80）。
 *
 * <p>两个端点：</p>
 * <ul>
 *     <li>{@code POST /api/chat}：发送用户问题，返回 AgentResult（可能含待确认动作）；</li>
 *     <li>{@code POST /api/confirm}：确认待执行的写动作，触发实际写入。</li>
 * </ul>
 *
 * <p><b>本类刻意保持极薄</b>：身份解析（{@link TicketIdentityConfiguration}）、
 * session 解析、幂等键生成、AgentRequest 拼装全部下沉到 {@link KeelAgentClient}。
 * 改造前这里混着 30 多行 Keel 管道代码（含硬编码租户与手拼幂等键），
 * 那是每个业务项目都会重写一遍、且最容易写错 P0 安全语义的部分。</p>
 *
 * <p>需要完全控制请求字段（自定义 skill 路由、手工幂等键）时，直接注入
 * {@code KeelAgent} 用底层 API——client 是便利层，不是唯一入口。</p>
 */
@RestController
@RequestMapping("/api")
public class TicketController {

    private final KeelAgentClient keelClient;

    public TicketController(KeelAgentClient keelClient) {
        this.keelClient = keelClient;
    }

    /**
     * 发送用户输入。
     *
     * <p>会话由客户端统一解析：显式参数 → {@code X-Session-Id} 请求头 →
     * 按身份派生的稳定值。缺省不传也能保持多轮语义（写确认依赖它恢复 checkpoint）。</p>
     *
     * <p>身份从 {@code X-Tenant-Id} / {@code X-User-Id} / {@code X-Resource-Ids}
     * 请求头读取，缺省落回 {@code keel.principal.*}。</p>
     */
    @PostMapping("/chat")
    public AgentResult chat(@RequestBody Map<String, String> body) {
        return keelClient.chat(body.get("input"), body.getOrDefault("skill", "ticket-ops"));
    }

    /** 确认待执行写动作：用上一步返回的 {@code pendingActions[].actionId}。 */
    @PostMapping("/confirm")
    public AgentResult confirm(@RequestBody Map<String, String> body) {
        return keelClient.confirm(body.get("actionId"));
    }
}
