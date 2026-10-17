package com.io.kira.adapter.chatbot.in.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatbotResponse(
        String reply,
        String message,
        java.util.UUID threadId
) {
    public ChatbotResponse(String reply, String message) { this(reply, message, null); }
    public static ChatbotResponse success(String reply, java.util.UUID threadId) { return new ChatbotResponse(reply, null, threadId); }
    public static ChatbotResponse success(String reply) {
        return new ChatbotResponse(reply, null);
    }

    public static ChatbotResponse fail(String message) {
        return new ChatbotResponse(null, message);
    }
}
