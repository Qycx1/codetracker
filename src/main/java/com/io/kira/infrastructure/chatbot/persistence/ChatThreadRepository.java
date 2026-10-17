package com.io.kira.infrastructure.chatbot.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface ChatThreadRepository extends JpaRepository<ChatThreadEntity, UUID> {
    Optional<ChatThreadEntity> findByThreadIdAndOwnerId(UUID threadId, UUID ownerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ChatThreadEntity t where t.threadId = :id and t.ownerId = :owner")
    Optional<ChatThreadEntity> lockOwned(@Param("id") UUID id, @Param("owner") UUID owner);
    @Query("select t from ChatThreadEntity t where t.ownerId = :owner and " +
        "((:classroom is null and t.classroomId is null) or t.classroomId = :classroom) order by t.updatedAt desc, t.threadId desc")
    Slice<ChatThreadEntity> listScope(@Param("owner") UUID owner, @Param("classroom") UUID classroom, Pageable pageable);
}
