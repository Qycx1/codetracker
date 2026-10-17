package com.io.kira.adapter.chatbot.in.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import com.io.kira.application.chatbot.command.ChatbotTurn;
import java.util.List;

import java.util.UUID;

public record ChatbotRequest(
        @NotBlank(message = "Message is required")
        @Size(max = 2_000, message = "Message must not exceed 2000 characters")
        String message,
        UUID classroomId,
        @Size(max = 10) List<@NotNull @Valid ChatbotTurn> history,
        @Size(max = 64) String timezone,
        UUID threadId,
        UUID requestId
) {
    public ChatbotRequest(String message, UUID classroomId, List<ChatbotTurn> history, String timezone) {
        this(message, classroomId, history, timezone, null, null);
    }
}
