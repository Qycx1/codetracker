package com.io.kira.adapter.chatbot.in.rest;

import com.io.kira.adapter.auth.out.security.AuthPrincipal;
import com.io.kira.adapter.chatbot.in.dto.request.ChatbotRequest;
import com.io.kira.adapter.chatbot.in.dto.response.ChatbotResponse;
import com.io.kira.application.chatbot.command.ChatbotCommand;
import com.io.kira.application.chatbot.error.ChatbotError;
import com.io.kira.application.chatbot.port.in.ChatbotUseCase;
import com.io.kira.application.chatbot.result.ChatbotReplyData;
import com.io.kira.common.result.Result;
import com.io.kira.application.chatbot.service.ChatbotConversationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chatbot")
public class ChatbotController {

    private final ChatbotUseCase chatbotUseCase;
    private final ChatbotConversationService conversations;

    public ChatbotController(ChatbotUseCase chatbotUseCase, ChatbotConversationService conversations) {
        this.chatbotUseCase = chatbotUseCase;
        this.conversations = conversations;
    }

    @PostMapping
    public ResponseEntity<ChatbotResponse> chat(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ChatbotRequest request
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ChatbotResponse.fail("You must be logged in to use Echo."));
        }

        Result<ChatbotReplyData, ChatbotError> result = request.threadId() != null ? conversations.reply(
                principal.getUserId(), request.threadId(), request.requestId() == null ? java.util.UUID.randomUUID() : request.requestId(),
                request.message().trim(), request.classroomId(), request.timezone()
        ) : chatbotUseCase.execute(
                new ChatbotCommand(
                        principal.getUserId(),
                        request.message().trim(),
                        request.classroomId(),
                        request.history(),
                        request.timezone()
                )
        );

        if (result.success()) {
            return ResponseEntity.ok(ChatbotResponse.success(result.data().reply(), request.threadId()));
        }

        return switch (result.error()) {
            case ACCESS_DENIED -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ChatbotResponse.fail(
                            "You do not have permission to access information from this classroom."
                    ));
            case PROVIDER_NOT_CONFIGURED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ChatbotResponse.fail(
                            "Echo's AI service is not configured. You can still use Quick help. Ask your project administrator to enable AI replies."
                    ));
            case PROVIDER_UNAVAILABLE -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ChatbotResponse.fail(
                            "Echo is temporarily unavailable. Please try again shortly."
                    ));
        };
    }
}
