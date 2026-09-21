package io.keel.examples.ticket;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.keel.core.KeelPrincipal;
import io.keel.graph.ToolCall;
import io.keel.graph.ToolObservation;
import io.keel.graph.spi.ToolPort;

/**
 * 示例自定义 ToolPort：把工单服务封装为图可调用的工具（FR-30/FR-31）。
 *
 * <p>工具清单：</p>
 * <ul>
 *     <li>{@code ticket.query}（只读）：按 ID 查工单；</li>
 *     <li>{@code ticket.create}（写）：创建工单，需用户确认后执行；</li>
 *     <li>{@code ticket.updateStatus}（写）：更新工单状态，需用户确认后执行。</li>
 * </ul>
 *
 * <p>写身份由 {@link #isWriteTool} 代码判定，图内核据此在确认前拒绝执行（FR-13）。
 * 写操作的幂等由 {@link TicketService} 用 actionId 保证（FR-32）。</p>
 */
@Component
public class TicketToolPort implements ToolPort {

    private static final Set<String> WRITE_TOOLS = Set.of("ticket.create", "ticket.updateStatus");

    private final TicketService ticketService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TicketToolPort(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public ToolObservation invoke(ToolCall call, KeelPrincipal principal) {
        String tool = call.getTool();
        Map<String, Object> args = parseArgs(call.getArgumentsJson());
        try {
            return switch (tool) {
            case "ticket.query" -> query(args);
            case "ticket.create" -> create(call, args, principal);
            case "ticket.updateStatus" -> updateStatus(call, args, principal);
            default -> ToolObservation.failed(tool, "未知工具: " + tool);
        };
        } catch (IllegalArgumentException e) {
            return ToolObservation.failed(tool, e.getMessage());
        } catch (Exception e) {
            return ToolObservation.failed(tool, "工具执行失败: " + e.getMessage());
        }
    }

    @Override
    public boolean isWriteTool(String tool) {
        return WRITE_TOOLS.contains(tool);
    }

    private ToolObservation query(Map<String, Object> args) {
        String id = stringArg(args, "id");
        if (id == null) {
            return ToolObservation.failed("ticket.query", "缺少参数 id");
        }
        Ticket ticket = ticketService.findById(id);
        if (ticket == null) {
            return ToolObservation.ok("ticket.query", "未找到工单: " + id);
        }
        return ToolObservation.ok("ticket.query",
                "工单 " + ticket.getId() + ": " + ticket.getTitle()
                        + "，状态: " + ticket.getStatus());
    }

    private ToolObservation create(ToolCall call, Map<String, Object> args, KeelPrincipal principal) {
        String title = stringArg(args, "title");
        if (title == null || title.isBlank()) {
            return ToolObservation.failed("ticket.create", "缺少参数 title");
        }
        // FR-32：优先用图内核透传的 idempotencyKey 作 actionId（请求级唯一，跨进程 dedupe），
        // 兼容旧调用方：args.actionId 作 fallback，principal+title 作最终兜底
        String actionId = call.getIdempotencyKey();
        if (actionId == null || actionId.isBlank()) {
            actionId = stringArg(args, "actionId");
        }
        if (actionId == null) {
            actionId = principal.getSubjectId() + ":" + title;
        }
        Ticket ticket = ticketService.create(actionId, title);
        return ToolObservation.ok("ticket.create",
                "工单已创建: " + ticket.getId() + " (" + ticket.getTitle() + ")");
    }

    private ToolObservation updateStatus(ToolCall call, Map<String, Object> args, KeelPrincipal principal) {
        String ticketId = stringArg(args, "ticketId");
        String status = stringArg(args, "status");
        if (ticketId == null || status == null) {
            return ToolObservation.failed("ticket.updateStatus",
                    "缺少参数 ticketId 或 status");
        }
        // FR-32：优先用图内核透传的 idempotencyKey 作 actionId，args.actionId 与 principal 兜底
        String actionId = call.getIdempotencyKey();
        if (actionId == null || actionId.isBlank()) {
            actionId = stringArg(args, "actionId");
        }
        if (actionId == null) {
            actionId = principal.getSubjectId() + ":" + ticketId + ":" + status;
        }
        Ticket ticket = ticketService.updateStatus(actionId, ticketId, status);
        return ToolObservation.ok("ticket.updateStatus",
                "工单 " + ticket.getId() + " 状态已更新为: " + ticket.getStatus());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : value.toString();
    }
}
