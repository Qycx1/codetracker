package com.io.kira.application.chatbot.service;

import com.io.kira.infrastructure.chatbot.persistence.*;
import com.io.kira.application.chatbot.command.ChatbotTurn;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

/** Short database transactions. No connection/row lock is held while the AI runs. */
@Service
public class ChatbotConversationStore {
    private final ChatThreadRepository threads;
    private final ChatMessageRepository messages;
    private final ChatbotAccessService access;
    public ChatbotConversationStore(ChatThreadRepository threads, ChatMessageRepository messages, ChatbotAccessService access) {
        this.threads = threads; this.messages = messages; this.access = access;
    }
    public record ThreadData(UUID threadId, UUID classroomId, String title, Instant createdAt, Instant updatedAt) {}
    public record MessageData(UUID messageId, String role, String content, long position, Instant createdAt) {}
    public record ThreadPage(List<ThreadData> data, int page, boolean hasMore) {}
    public record MessagePage(List<MessageData> data, boolean hasMore, Long nextBeforePosition) {}
    public record Reservation(UUID classroomId, List<ChatbotTurn> history, String replay) {}
    private ThreadData data(ChatThreadEntity t) { return new ThreadData(t.getThreadId(), t.getClassroomId(), t.getTitle(), t.getCreatedAt(), t.getUpdatedAt()); }
    private void classroomAccess(UUID owner, UUID classroom) {
        if (classroom != null && access.getAccess(owner, classroom) == ChatbotAccessService.ClassroomAccess.NONE)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Classroom access is no longer available");
    }
    private ChatThreadEntity owned(UUID owner, UUID id, boolean lock, boolean checkClassroom) {
        ChatThreadEntity t = (lock ? threads.lockOwned(id, owner) : threads.findByThreadIdAndOwnerId(id, owner))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (checkClassroom) classroomAccess(owner, t.getClassroomId());
        return t;
    }
    @Transactional
    public ThreadData create(UUID owner, UUID id, UUID classroom, String title) {
        classroomAccess(owner, classroom);
        if (id == null) id = UUID.randomUUID();
        var existing = threads.findById(id);
        if (existing.isPresent()) {
            var t = owned(owner, id, false, true);
            if (!Objects.equals(t.getClassroomId(), classroom)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Conversation context differs");
            return data(t);
        }
        var t = new ChatThreadEntity();
        t.setThreadId(id); t.setOwnerId(owner); t.setClassroomId(classroom);
        t.setTitle(title == null || title.isBlank() ? "New conversation" : title.trim());
        t.setCreatedAt(Instant.now()); t.setUpdatedAt(t.getCreatedAt()); t.setNextPosition(1);
        threads.save(t); return data(t);
    }
    @Transactional(readOnly = true)
    public ThreadPage list(UUID owner, UUID classroom, int page, int size) {
        classroomAccess(owner, classroom);
        var result = threads.listScope(owner, classroom, PageRequest.of(page, size));
        return new ThreadPage(result.getContent().stream().map(this::data).toList(), page, result.hasNext());
    }
    @Transactional(readOnly = true)
    public MessagePage read(UUID owner, UUID id, long before, int size) {
        owned(owner, id, false, true);
        var rows = new ArrayList<>(messages.findByThreadIdAndPositionLessThanOrderByPositionDesc(id, before, PageRequest.of(0, size + 1)));
        boolean more = rows.size() > size;
        if (more) rows.removeLast();
        Collections.reverse(rows);
        var data = rows.stream().map(m -> new MessageData(m.getMessageId(), m.getRole(), m.getContent(), m.getPosition(), m.getCreatedAt())).toList();
        return new MessagePage(data, more, more && !rows.isEmpty() ? rows.getFirst().getPosition() : null);
    }
    @Transactional
    public ThreadData rename(UUID owner, UUID id, String title) {
        var t = owned(owner, id, true, true);
        t.setTitle(title.trim()); t.setUpdatedAt(Instant.now()); return data(t);
    }
    @Transactional
    public void delete(UUID owner, UUID id) {
        // Owners can delete their saved conversations even after leaving a class.
        var t = owned(owner, id, true, false);
        messages.deleteByThreadId(id); threads.delete(t);
    }
    @Transactional
    public Reservation reserve(UUID owner, UUID id, UUID request, UUID classroom, String text) {
        var t = owned(owner, id, true, true);
        if (!Objects.equals(classroom, t.getClassroomId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Conversation context differs");
        var answer = messages.findByThreadIdAndRequestIdAndRole(id, request, "assistant");
        if (answer.isPresent()) {
            var question = messages.findByThreadIdAndRequestIdAndRole(id, request, "user");
            if (question.isEmpty() || !question.get().getContent().equals(text))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request identifier already used");
            return new Reservation(t.getClassroomId(), List.of(), answer.get().getContent());
        }
        if (t.getPendingUntil() != null && t.getPendingUntil().isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A reply is already in progress. Reload this conversation shortly.");
        t.setPendingRequestId(request); t.setPendingUntil(Instant.now().plusSeconds(120));
        var rows = new ArrayList<>(messages.findByThreadIdAndPositionLessThanOrderByPositionDesc(id, Long.MAX_VALUE, PageRequest.of(0, 10)));
        Collections.reverse(rows);
        return new Reservation(t.getClassroomId(), rows.stream().map(m -> new ChatbotTurn(m.getRole(), m.getContent().substring(0, Math.min(2000, m.getContent().length())))).toList(), null);
    }
    private ChatMessageEntity message(ChatThreadEntity t, UUID request, String role, String text) {
        var m = new ChatMessageEntity(); m.setMessageId(UUID.randomUUID()); m.setThreadId(t.getThreadId());
        m.setRequestId(request); m.setRole(role); m.setContent(text); m.setPosition(t.getNextPosition());
        t.setNextPosition(t.getNextPosition() + 1); m.setCreatedAt(Instant.now()); return m;
    }
    @Transactional
    public void finish(UUID owner, UUID id, UUID request, String question, String answer) {
        var t = owned(owner, id, true, true);
        if (!request.equals(t.getPendingRequestId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "This reply was superseded. Reload the conversation.");
        messages.save(message(t, request, "user", question));
        messages.save(message(t, request, "assistant", answer.substring(0, Math.min(12000, answer.length()))));
        t.setPendingRequestId(null); t.setPendingUntil(null); t.setUpdatedAt(Instant.now());
    }
    @Transactional
    public void release(UUID owner, UUID id, UUID request) {
        threads.lockOwned(id, owner).filter(t -> request.equals(t.getPendingRequestId())).ifPresent(t -> {
            t.setPendingRequestId(null); t.setPendingUntil(null);
        });
    }
}
