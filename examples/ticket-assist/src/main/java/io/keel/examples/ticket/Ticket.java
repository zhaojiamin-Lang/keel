package io.keel.examples.ticket;

import java.time.Instant;

/**
 * 工单领域模型（示例用内存存储，非框架内核）。
 */
public final class Ticket {

    private final String id;
    private final String title;
    private final String status;
    private final Instant createdAt;

    public Ticket(String id, String title, String status, Instant createdAt) {
        this.id = id;
        this.title = title;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
