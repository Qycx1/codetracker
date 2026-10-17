package com.io.kira.adapter.chatbot.in.rest;

import com.io.kira.adapter.auth.out.security.AuthPrincipal;
import com.io.kira.application.chatbot.service.ChatbotConversationStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@RestController @RequestMapping("/api/chatbot/threads")
public class ChatbotHistoryController {
    private final ChatbotConversationStore store;
    public ChatbotHistoryController(ChatbotConversationStore store) { this.store = store; }
    public record Create(UUID threadId, UUID classroomId, @Size(max = 80) String title) {}
    public record Rename(@NotBlank @Size(max = 80) String title) {}
    private UUID owner(AuthPrincipal p) {
        if (p == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to use conversations");
        return p.getUserId();
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ChatbotConversationStore.ThreadData create(@AuthenticationPrincipal AuthPrincipal p, @Valid @RequestBody Create body) {
        return store.create(owner(p), body.threadId(), body.classroomId(), body.title());
    }
    @GetMapping
    public ChatbotConversationStore.ThreadPage list(@AuthenticationPrincipal AuthPrincipal p,
        @RequestParam(required = false) UUID classroomId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return store.list(owner(p), classroomId, Math.max(0, Math.min(100000, page)), Math.max(1, Math.min(100, size)));
    }
    @GetMapping("/{id}/messages")
    public ChatbotConversationStore.MessagePage messages(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id,
        @RequestParam(defaultValue = "9223372036854775807") long beforePosition, @RequestParam(defaultValue = "100") int size) {
        return store.read(owner(p), id, Math.max(1, beforePosition), Math.max(1, Math.min(100, size)));
    }
    @PatchMapping("/{id}")
    public ChatbotConversationStore.ThreadData rename(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @Valid @RequestBody Rename body) {
        return store.rename(owner(p), id, body.title());
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) { store.delete(owner(p), id); }
}
