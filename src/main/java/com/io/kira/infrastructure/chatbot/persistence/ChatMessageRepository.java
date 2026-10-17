package com.io.kira.infrastructure.chatbot.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, UUID> {
    List<ChatMessageEntity> findByThreadIdAndPositionLessThanOrderByPositionDesc(UUID thread, long before, Pageable pageable);
    Optional<ChatMessageEntity> findByThreadIdAndRequestIdAndRole(UUID thread, UUID request, String role);
    void deleteByThreadId(UUID thread);
}
