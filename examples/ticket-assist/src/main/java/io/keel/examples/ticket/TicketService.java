package io.keel.examples.ticket;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

/**
 * 内存工单服务（示例用，非框架内核）。
 *
 * <p>写操作（create / updateStatus）带幂等键：同一 actionId 重复调用不产生第二笔副作用（FR-32）。
 * 真实业务应把幂等表落在业务 DB，这里用内存 Map 演示机制。</p>
 */
@Service
public class TicketService {

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, Ticket> idempotency = new ConcurrentHashMap<>();
    /** 从 3 起步，避开预置的 T-001 / T-002，避免新建工单覆盖预置数据 */
    private final AtomicLong idSeq = new AtomicLong(3);

    public TicketService() {
        // 预置两条示例工单，供只读查询演示
        tickets.put("T-001", new Ticket("T-001", "登录失败", "OPEN", Instant.now()));
        tickets.put("T-002", new Ticket("T-002", "导出报表慢", "IN_PROGRESS", Instant.now()));
    }

    public Ticket findById(String id) {
        return tickets.get(id);
    }

    public Collection<Ticket> listAll() {
        return tickets.values();
    }

    /**
     * 创建工单（写操作）。
     *
     * @param actionId 幂等键，同一 actionId 重复调用返回首次创建的工单，不重复建单（FR-32）
     */
    public Ticket create(String actionId, String title) {
        return idempotency.computeIfAbsent(actionId, key -> {
            String id = "T-" + String.format("%03d", idSeq.getAndIncrement());
            Ticket ticket = new Ticket(id, title, "OPEN", Instant.now());
            tickets.put(id, ticket);
            return ticket;
        });
    }

    /**
     * 更新工单状态（写操作）。
     *
     * @param actionId 幂等键（FR-32）
     */
    public Ticket updateStatus(String actionId, String ticketId, String status) {
        Ticket existing = tickets.get(ticketId);
        if (existing == null) {
            throw new IllegalArgumentException("工单不存在: " + ticketId);
        }
        return idempotency.computeIfAbsent(actionId, key -> {
            Ticket updated = new Ticket(existing.getId(), existing.getTitle(), status,
                    existing.getCreatedAt());
            tickets.put(ticketId, updated);
            return updated;
        });
    }
}
