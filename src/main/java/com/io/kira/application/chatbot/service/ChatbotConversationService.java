package com.io.kira.application.chatbot.service;

import com.io.kira.application.chatbot.command.ChatbotCommand;
import com.io.kira.application.chatbot.error.ChatbotError;
import com.io.kira.application.chatbot.port.in.ChatbotUseCase;
import com.io.kira.application.chatbot.result.ChatbotReplyData;
import com.io.kira.common.result.Result;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class ChatbotConversationService {
    private final ChatbotConversationStore store;
    private final ChatbotUseCase chatbot;
    public ChatbotConversationService(ChatbotConversationStore store, ChatbotUseCase chatbot) { this.store = store; this.chatbot = chatbot; }
    public Result<ChatbotReplyData, ChatbotError> reply(UUID owner, UUID thread, UUID request, String message, UUID classroom, String timezone) {
        var reserved = store.reserve(owner, thread, request, classroom, message);
        if (reserved.replay() != null) return Result.ok(new ChatbotReplyData(reserved.replay()));
        try {
            // History comes from owned server records. Private context is refreshed by the existing use case.
            var result = chatbot.execute(new ChatbotCommand(owner, message, reserved.classroomId(), reserved.history(), timezone));
            if (result.success()) store.finish(owner, thread, request, message, result.data().reply());
            return result;
        } finally { store.release(owner, thread, request); }
    }
}
