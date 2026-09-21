package io.keel.model;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;

public class SingleShotKeelAgent implements KeelAgent {

    private static final String SYSTEM_PROMPT =
            "你是助手，不要捏造业务 ID，不要假装检索过知识。";

    private final ChatModel chatModel;

    public SingleShotKeelAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public AgentResult run(AgentRequest request) {
        if (request == null || request.getInput() == null || request.getInput().isBlank()) {
            return AgentResult.builder()
                    .status(AgentStatus.ERROR)
                    .text("")
                    .errorCode("INVALID_REQUEST")
                    .errorMessage("request or input is required")
                    .build();
        }

        try {
            Prompt prompt = new Prompt(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(request.getInput()));
            ChatResponse response = chatModel.call(prompt);

            String text = "";
            if (response != null
                    && response.getResults() != null
                    && !response.getResults().isEmpty()
                    && response.getResults().get(0) != null
                    && response.getResults().get(0).getOutput() != null) {
                text = response.getResults().get(0).getOutput().getContent();
            }

            return AgentResult.builder()
                    .status(AgentStatus.SUCCESS)
                    .text(text == null ? "" : text)
                    .citations(List.of())
                    .pendingActions(List.of())
                    .traceId(UUID.randomUUID().toString())
                    .build();
        } catch (RuntimeException exception) {
            return AgentResult.builder()
                    .status(AgentStatus.ERROR)
                    .text("")
                    .errorCode("MODEL_ERROR")
                    .errorMessage("model call failed: " + exception.getMessage())
                    .traceId(UUID.randomUUID().toString())
                    .build();
        }
    }
}
