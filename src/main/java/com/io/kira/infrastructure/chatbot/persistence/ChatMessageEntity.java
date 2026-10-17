package com.io.kira.infrastructure.chatbot.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "echo_messages", schema = "public", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"thread_id", "position"}),
    @UniqueConstraint(columnNames = {"thread_id", "request_id", "role"})
})
@Getter @Setter @NoArgsConstructor
public class ChatMessageEntity {
    @Id @Column(name = "message_id") private UUID messageId;
    @Column(name = "thread_id", nullable = false) private UUID threadId;
    @Column(name = "request_id", nullable = false) private UUID requestId;
    @Column(nullable = false, length = 9) private String role;
    @Column(nullable = false, columnDefinition = "text") private String content;
    @Column(nullable = false) private long position;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
}
