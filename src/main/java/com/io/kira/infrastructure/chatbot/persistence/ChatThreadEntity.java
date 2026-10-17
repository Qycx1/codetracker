package com.io.kira.infrastructure.chatbot.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "echo_threads", schema = "public")
@Getter @Setter @NoArgsConstructor
public class ChatThreadEntity {
    @Id @Column(name = "thread_id") private UUID threadId;
    @Column(name = "owner_id", nullable = false, updatable = false) private UUID ownerId;
    @Column(name = "classroom_id") private UUID classroomId;
    @Column(nullable = false, length = 80) private String title;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "next_position", nullable = false) private long nextPosition;
    @Column(name = "pending_request_id") private UUID pendingRequestId;
    @Column(name = "pending_until") private Instant pendingUntil;
}
