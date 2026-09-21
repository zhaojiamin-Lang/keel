package io.keel.examples.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ticket-assist 示例启动类（FR-80）。
 *
 * <p>演示 Keel 的两个核心场景：</p>
 * <ul>
 *     <li>只读问答：Agent 检索工单知识并带 citation 回答；</li>
 *     <li>确认后写入：Agent 提议建单/改状态，返回待确认动作，用户确认后执行写入。</li>
 * </ul>
 */
@SpringBootApplication
public class TicketAssistApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketAssistApplication.class, args);
    }
}
