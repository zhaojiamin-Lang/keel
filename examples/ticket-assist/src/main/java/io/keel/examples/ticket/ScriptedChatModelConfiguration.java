package io.keel.examples.ticket;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 脚本化模型桩配置：让 ticket-assist 示例开箱即用，无需配置真实模型 API Key。
 *
 * <p>用户配置了真实模型（spring.ai.* 对应的 ChatModel bean）后，
 * {@link ConditionalOnMissingBean} 会自动让位于真实模型。</p>
 */
@Configuration
public class ScriptedChatModelConfiguration {

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel scriptedChatModel() {
        return new ScriptedChatModel();
    }

    /**
     * 根据规划 prompt 中的用户输入关键词返回预定义的 JSON 决策：
     * <ul>
     *     <li>含「创建/建单」→ PROPOSE_WRITE ticket.create；</li>
     *     <li>含「改状态/关闭」→ PROPOSE_WRITE ticket.updateStatus；</li>
     *     <li>含「查/查询/看看」→ CALL_TOOL ticket.query；</li>
     *     <li>其余 → ANSWER。</li>
     * </ul>
     * Critic 一律 APPROVED。
     */
    static class ScriptedChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            String userText = extractUserText(prompt);
            String output;
            if (userText.contains("[KEEL-CRITIC]")) {
                output = "{\"verdict\":\"APPROVED\"}";
            } else {
                output = planOutput(userText);
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
        }

        private String extractUserText(Prompt prompt) {
            for (Message message : prompt.getInstructions()) {
                if (message instanceof org.springframework.ai.chat.messages.UserMessage) {
                    return message.getText();
                }
            }
            return "";
        }

        private String planOutput(String userText) {
            // 图循环里一旦已有工具观察，就直接基于观察内容作答：
            // 否则只读查询会在 CALL_TOOL 上循环到 MAX_STEPS_EXCEEDED，
            // 确认写入执行后也会重新 PROPOSE_WRITE 导致 confirm 循环不收敛
            if (userText.contains("已有工具观察:")) {
                return "{\"action\":\"ANSWER\",\"answer\":\""
                        + jsonEscape(observationSummary(userText)) + "\"}";
            }
            String input = extractInputLine(userText);
            if (input.contains("创建") || input.contains("建单") || input.contains("新建")) {
                return "{\"action\":\"PROPOSE_WRITE\",\"tool\":\"ticket.create\","
                        + "\"arguments\":{\"title\":\"" + safeTitle(input) + "\"}}";
            }
            if (input.contains("改状态") || input.contains("关闭") || input.contains("更新状态")) {
                return "{\"action\":\"PROPOSE_WRITE\",\"tool\":\"ticket.updateStatus\","
                        + "\"arguments\":{\"ticketId\":\"T-001\",\"status\":\"CLOSED\"}}";
            }
            if (input.contains("查") || input.contains("看看") || input.contains("查询")) {
                return "{\"action\":\"CALL_TOOL\",\"tool\":\"ticket.query\","
                        + "\"arguments\":{\"id\":\"T-001\"}}";
            }
            return "{\"action\":\"ANSWER\",\"answer\":\"我可以帮你查询工单、创建工单或更新工单状态。\"}";
        }

        private String extractInputLine(String text) {
            int idx = text.indexOf("用户输入:");
            if (idx < 0) {
                return text;
            }
            int end = text.indexOf('\n', idx);
            return end < 0 ? text.substring(idx) : text.substring(idx, end);
        }

        /**
         * 从规划提示词的「已有工具观察」段落提取首条观察摘要：
         * 成功取 content，失败取 error，取不到时给通用答复。
         */
        private String observationSummary(String userText) {
            int section = userText.indexOf("已有工具观察:");
            if (section < 0) {
                return "工具调用已完成。";
            }
            String rest = userText.substring(section);
            int contentIdx = rest.indexOf("content=");
            if (contentIdx >= 0) {
                return "查询结果: " + firstLine(rest.substring(contentIdx + "content=".length()));
            }
            int errorIdx = rest.indexOf("error=");
            if (errorIdx >= 0) {
                return "工具执行失败: " + firstLine(rest.substring(errorIdx + "error=".length()));
            }
            return "工具调用已完成。";
        }

        private String firstLine(String text) {
            int end = text.indexOf('\n');
            String line = end < 0 ? text : text.substring(0, end);
            return line.strip();
        }

        /** JSON 字符串转义，防止观察内容里的引号破坏决策 JSON。 */
        private String jsonEscape(String text) {
            return text.replace("\\", "\\\\").replace("\"", "\\'");
        }

        private String safeTitle(String input) {
            String title = input.replace("用户输入:", "").trim();
            if (title.length() > 30) {
                title = title.substring(0, 30);
            }
            return title.replace("\"", "'");
        }
    }
}
