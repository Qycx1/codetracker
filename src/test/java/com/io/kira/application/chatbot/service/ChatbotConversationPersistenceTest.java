package com.io.kira.application.chatbot.service;

import com.io.kira.infrastructure.chatbot.persistence.*;
import com.io.kira.application.chatbot.command.ChatbotCommand;
import com.io.kira.application.chatbot.error.ChatbotError;
import com.io.kira.application.chatbot.port.in.ChatbotUseCase;
import com.io.kira.application.chatbot.result.ChatbotReplyData;
import com.io.kira.common.result.Result;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real JPA transactions, pagination and ownership. No Supabase/Gemini credentials. */
class ChatbotConversationPersistenceTest {
    static AnnotationConfigApplicationContext context;
    ChatbotConversationStore store;
    Access access;
    UUID owner, classroom, thread;
    static class Access extends ChatbotAccessService {
        boolean allowed = true;
        Access() { super(null, null); }
        @Override public ClassroomAccess getAccess(UUID user, UUID classroom) { return allowed ? ClassroomAccess.STUDENT : ClassroomAccess.NONE; }
    }
    @BeforeAll static void database() { context = new AnnotationConfigApplicationContext(Config.class); }
    @AfterAll static void close() { if (context != null) context.close(); }
    @BeforeEach void setup() {
        store = context.getBean(ChatbotConversationStore.class); access = context.getBean(Access.class); access.allowed = true;
        owner = UUID.randomUUID(); classroom = UUID.randomUUID();
        thread = store.create(owner, null, null, "My conversation").threadId();
    }
    void status(int expected, Runnable action) { assertEquals(expected, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value()); }
    ChatbotConversationService service(ChatbotUseCase useCase) { return new ChatbotConversationService(store, useCase); }

    @Test void successfulTurnsPersistAndRestoreForANewServiceSession() {
        UUID request = UUID.randomUUID();
        var first = service(command -> Result.ok(new ChatbotReplyData("Saved response")));
        assertTrue(first.reply(owner, thread, request, "Hello", null, "Asia/Manila").success());
        var history = store.read(owner, thread, Long.MAX_VALUE, 100).data();
        assertEquals(List.of("Hello", "Saved response"), history.stream().map(ChatbotConversationStore.MessageData::content).toList());
        var second = service(command -> {
            assertEquals("Hello", command.history().getFirst().text());
            assertEquals("Saved response", command.history().getLast().text());
            return Result.ok(new ChatbotReplyData("Continued"));
        });
        second.reply(owner, thread, UUID.randomUUID(), "Continue", null, "UTC");
        assertEquals(4, store.read(owner, thread, Long.MAX_VALUE, 100).data().size());
        assertEquals(thread, store.list(owner, null, 0, 50).data().getFirst().threadId());
    }
    @Test void duplicateRequestReplaysSavedAnswerWithoutCallingAIOrDuplicatingMessages() {
        UUID request = UUID.randomUUID();
        service(c -> Result.ok(new ChatbotReplyData("One answer"))).reply(owner, thread, request, "Question", null, "UTC");
        var replay = service(c -> { fail("AI must not run twice"); return null; }).reply(owner, thread, request, "Question", null, "UTC");
        assertEquals("One answer", replay.data().reply());
        assertEquals(2, store.read(owner, thread, Long.MAX_VALUE, 100).data().size());
        status(409, () -> store.reserve(owner, thread, request, null, "Different question"));
    }
    @Test void otherAccountsCannotReadRenameDeleteReuseOrSendToAThread() {
        UUID other = UUID.randomUUID();
        assertTrue(store.list(other, null, 0, 50).data().isEmpty());
        status(404, () -> store.read(other, thread, Long.MAX_VALUE, 100));
        status(404, () -> store.rename(other, thread, "Stolen"));
        status(404, () -> store.delete(other, thread));
        status(404, () -> store.create(other, thread, null, "Reused"));
        status(404, () -> store.reserve(other, thread, UUID.randomUUID(), null, "Read it"));
    }
    @Test void classroomScopeCannotBeChangedAndRevokedAccessBlocksReadAndSend() {
        UUID classThread = store.create(owner, null, classroom, "Class conversation").threadId();
        assertEquals(1, store.list(owner, classroom, 0, 50).data().size());
        assertEquals(1, store.list(owner, null, 0, 50).data().size());
        status(409, () -> store.reserve(owner, classThread, UUID.randomUUID(), null, "Wrong context"));
        access.allowed = false;
        status(403, () -> store.read(owner, classThread, Long.MAX_VALUE, 100));
        status(403, () -> store.reserve(owner, classThread, UUID.randomUUID(), classroom, "Denied"));
        store.delete(owner, classThread); // Owners retain the right to remove saved data.
    }
    @Test void failedAILeavesNoHalfSavedTurnAndReleasesTheLeaseForRetry() {
        UUID request = UUID.randomUUID();
        assertFalse(service(c -> Result.fail(ChatbotError.PROVIDER_UNAVAILABLE)).reply(owner, thread, request, "Hello", null, "UTC").success());
        assertTrue(store.read(owner, thread, Long.MAX_VALUE, 100).data().isEmpty());
        assertTrue(service(c -> Result.ok(new ChatbotReplyData("Retry worked"))).reply(owner, thread, request, "Hello", null, "UTC").success());
        assertEquals(2, store.read(owner, thread, Long.MAX_VALUE, 100).data().size());
    }
    @Test void aBusyThreadRejectsOverlappingTurnsAndAnExpiredReservationCanRecover() {
        UUID request = UUID.randomUUID();
        store.reserve(owner, thread, request, null, "First");
        status(409, () -> store.reserve(owner, thread, UUID.randomUUID(), null, "Second"));
        new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class))
            .executeWithoutResult(s -> context.getBean(EntityManager.class).find(ChatThreadEntity.class, thread)
                .setPendingUntil(java.time.Instant.now().minusSeconds(1)));
        UUID next = UUID.randomUUID();
        store.reserve(owner, thread, next, null, "Next");
        status(409, () -> store.finish(owner, thread, request, "First", "Stale answer"));
        store.finish(owner, thread, next, "Next", "Current answer");
        assertEquals("Next", store.read(owner, thread, Long.MAX_VALUE, 100).data().getFirst().content());
    }
    @Test void cursorPaginationReturnsOrderedNonOverlappingPagesAndDatabaseHistoryIsBoundedForAI() {
        var bot = service(c -> Result.ok(new ChatbotReplyData("Answer")));
        for (int i = 0; i < 7; i++) bot.reply(owner, thread, UUID.randomUUID(), "Question " + i, null, "UTC");
        var page = store.read(owner, thread, Long.MAX_VALUE, 5);
        assertTrue(page.hasMore()); assertEquals(5, page.data().size());
        var older = store.read(owner, thread, page.nextBeforePosition(), 5);
        assertTrue(older.data().getLast().position() < page.data().getFirst().position());
        assertEquals(10, store.reserve(owner, thread, UUID.randomUUID(), null, "Follow up").history().size());
    }
    @Test void renameAndDeleteAreDurableAndDeleteRemovesMessages() {
        service(c -> Result.ok(new ChatbotReplyData("Answer"))).reply(owner, thread, UUID.randomUUID(), "Hello", null, "UTC");
        store.rename(owner, thread, "Renamed");
        assertEquals("Renamed", store.list(owner, null, 0, 50).data().getFirst().title());
        store.delete(owner, thread);
        status(404, () -> store.read(owner, thread, Long.MAX_VALUE, 100));
        assertTrue(context.getBean(ChatMessageRepository.class).findByThreadIdAndPositionLessThanOrderByPositionDesc(thread, Long.MAX_VALUE,
            org.springframework.data.domain.PageRequest.of(0, 100)).isEmpty());
    }
    @Test void deletingAnInFlightThreadPreventsLateReplyResurrection() {
        UUID request = UUID.randomUUID(); store.reserve(owner, thread, request, null, "Hello");
        store.delete(owner, thread);
        status(404, () -> store.finish(owner, thread, request, "Hello", "Late"));
        store.release(owner, thread, request);
        assertTrue(store.list(owner, null, 0, 50).data().isEmpty());
    }
    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() { return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build(); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter()); factory.setPackagesToScan("com.io.kira.infrastructure.chatbot.persistence");
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop")); return factory;
        }
        @Bean @Primary EntityManager entityManager(EntityManagerFactory f) { return SharedEntityManagerCreator.createSharedEntityManager(f); }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f) { return new JpaTransactionManager(f); }
        @Bean ChatThreadRepository threads(EntityManager em) { return new JpaRepositoryFactory(em).getRepository(ChatThreadRepository.class); }
        @Bean ChatMessageRepository messages(EntityManager em) { return new JpaRepositoryFactory(em).getRepository(ChatMessageRepository.class); }
        @Bean Access access() { return new Access(); }
        @Bean ChatbotConversationStore store(ChatThreadRepository t, ChatMessageRepository m, Access a) { return new ChatbotConversationStore(t, m, a); }
    }
}
