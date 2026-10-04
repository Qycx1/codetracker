package com.io.kira.application.activity.service;

import com.io.kira.adapter.activity.out.cache.StudentActivityCacheKey;
import com.io.kira.adapter.activity.out.cache.ActivityCacheKey;
import com.io.kira.adapter.activity.out.persistence.repository.ActivityGithubSubmissionAppAdapter;
import com.io.kira.adapter.activity.out.persistence.repository.StudentActivityAppRepositoryImpl;
import com.io.kira.adapter.classroom.out.cache.ClassroomRecentActivityCacheVersion;
import com.io.kira.adapter.github.out.persistence.repository.GithubSubmissionAppRepositoryImpl;
import com.io.kira.application.activity.port.out.StudentActivityAppRepository;
import com.io.kira.application.activity.port.out.ActivityGithubSubmissionAppPort;
import com.io.kira.application.classroom.result.ClassroomRecentActivityData;
import com.io.kira.application.github.port.in.CreateGithubSubmissionUseCase;
import com.io.kira.application.github.port.out.GithubSubmissionIntegrationPort;
import com.io.kira.application.github.result.GithubRepositoryData;
import com.io.kira.application.github.service.GithubSubmissionRegistration;
import com.io.kira.domain.activity.entity.StudentActivity;
import com.io.kira.domain.activity.valueObject.ActivityStatus;
import com.io.kira.domain.activity.valueObject.SubmissionStatus;
import com.io.kira.domain.classroom.valueObject.ClassroomStatus;
import com.io.kira.domain.github.valueobject.GithubSubmissionMode;
import com.io.kira.infrastructure.activity.persistence.entity.ActivityEntity;
import com.io.kira.infrastructure.activity.persistence.entity.StudentActivityEntity;
import com.io.kira.infrastructure.activity.persistence.repository.JpaActivityRepository;
import com.io.kira.infrastructure.activity.persistence.repository.JpaStudentActivityRepository;
import com.io.kira.infrastructure.classroom.persistence.entity.ClassroomEntity;
import com.io.kira.infrastructure.classroom.persistence.entity.ClassroomSettingsEntity;
import com.io.kira.infrastructure.github.persistence.entity.GithubSubmissionEntity;
import com.io.kira.infrastructure.github.persistence.repository.JpaGithubSubmissionRepository;
import com.io.kira.infrastructure.user.persistence.entity.UserEntity;
import com.io.kira.infrastructure.user.persistence.repository.JpaUserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.*;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real JPA transactions with H2; no deployment, GitHub, or Redis credentials. */
class ActivitySubmissionPersistenceTest {
    private static AnnotationConfigApplicationContext context;
    private EntityManager entityManager;
    private TransactionTemplate transaction;
    private ActivityRepositoryRegistration registration;
    private StudentActivityAppRepository studentActivities;
    private UUID userId;
    private UUID classroomId;
    private UUID activityId;

    @BeforeAll
    static void openDatabase() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "test-encryption", Map.of("encryption.password", "test-only-password",
                        "encryption.salt", "0011223344556677")));
        context.register(TestConfig.class);
        context.refresh();
    }

    @AfterAll
    static void closeDatabase() {
        if (context != null) context.close();
    }

    @BeforeEach
    void createAssignment() {
        entityManager = context.getBean(EntityManager.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        registration = context.getBean(ActivityRepositoryRegistration.class);
        studentActivities = context.getBean(StudentActivityAppRepository.class);
        var github = context.getBean(GithubSubmissionIntegrationPort.class);
        reset(github);
        when(github.findByRepository(anyString(), anyString())).thenReturn(Optional.of(
                new GithubRepositoryData("student", "123", "assignment")));
        userId = UUID.randomUUID();
        classroomId = UUID.randomUUID();
        activityId = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            var user = new UserEntity();
            user.setUserId(userId);
            user.setHasFullyInitialized(true);
            entityManager.persist(user);
            var classroom = new ClassroomEntity();
            classroom.setClassroomId(classroomId);
            classroom.setInstructorUserId(UUID.randomUUID());
            classroom.setName("Test classroom");
            classroom.setClassCode(classroomId.toString().substring(0, 20));
            classroom.setStatus(ClassroomStatus.ACTIVE);
            classroom.setSettings(new ClassroomSettingsEntity());
            entityManager.persist(classroom);
            var activity = new ActivityEntity();
            activity.setActivityId(activityId);
            activity.setClassroomEntity(classroom);
            activity.setTitle("Test assignment");
            activity.setStatus(ActivityStatus.PUBLISHED);
            entityManager.persist(activity);
        });
    }

    private StudentActivity attach() {
        return registration.register("test-token", classroomId, activityId, userId,
                "https://github.com/student/assignment", GithubSubmissionMode.EXISTING);
    }

    @Test
    void firstAttachmentCreatesBothRecordsAndLeavesWorkPending() {
        // Cache configuration deliberately rejects nulls, as production Redis does.
        assertTrue(studentActivities.findByUserIdAndActivityId(userId, activityId).isEmpty());
        assertTrue(studentActivities.findRepositoryUrlByUserIdAndActivityId(userId, activityId).isEmpty());
        var saved = attach();
        transaction.executeWithoutResult(status -> {
            var row = entityManager.find(StudentActivityEntity.class, saved.getStudentActivityId());
            assertNotNull(row.getGithubSubmission());
            assertEquals(SubmissionStatus.PENDING, row.getSubmissionStatus());
            assertNull(row.getGithubSubmission().getSubmittedAt());
        });
        assertTrue(studentActivities.existsSubmission(userId, activityId));
        assertEquals(Optional.of("https://github.com/student/assignment"),
                studentActivities.findRepositoryUrlByUserIdAndActivityId(userId, activityId));
    }

    @Test
    void failedGithubRegistrationRollsBackTheFirstInsertAndAllowsRetry() {
        var github = context.getBean(GithubSubmissionIntegrationPort.class);
        when(github.findByRepository(anyString(), anyString())).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, this::attach);
        assertTrue(studentActivities.findByUserIdAndActivityId(userId, activityId).isEmpty());
        assertFalse(studentActivities.existsSubmission(userId, activityId));
        when(github.findByRepository(anyString(), anyString())).thenReturn(Optional.of(
                new GithubRepositoryData("student", "123", "assignment")));
        assertNotNull(attach());
    }

    @Test
    void failedDatabaseLinkRollsBackInsteadOfLeavingAnOrphan() {
        var github = context.getBean(GithubSubmissionIntegrationPort.class);
        when(github.findByRepository(anyString(), anyString())).thenReturn(Optional.of(
                new GithubRepositoryData("student", null, "assignment")));
        assertThrows(RuntimeException.class, this::attach);
        assertTrue(studentActivities.findByUserIdAndActivityId(userId, activityId).isEmpty());
    }

    @Test
    void oldPendingOrphanCanBeAttachedWithoutCreatingADuplicateStudentActivity() {
        var orphan = StudentActivity.createNew(activityId, userId);
        studentActivities.save(orphan);
        assertFalse(studentActivities.existsSubmission(userId, activityId));
        var unsubmitted = context.getBean(ActivityGithubSubmissionAppPort.class);
        assertEquals(1, unsubmitted.getUnsubmittedRepositoryActivity(classroomId, userId).size());
        assertEquals(orphan.getStudentActivityId(), attach().getStudentActivityId());
        assertTrue(unsubmitted.getUnsubmittedRepositoryActivity(classroomId, userId).isEmpty());
    }

    @Test
    void dashboardFeedKeepsRepositoryAttachmentVisibleBeforeFinalSubmission() {
        attach();
        var events = context.getBean(JpaGithubSubmissionRepository.class)
                .findRecentRepositorySubmissionsByClassroomId(classroomId, PageRequest.of(0, 10));
        assertEquals(1, events.size());
        var event = ClassroomRecentActivityData.fromRepositorySubmitted(events.getFirst());
        assertEquals("REPOSITORY_SUBMITTED", event.eventType());
        assertNotNull(event.occurredAt());
        assertEquals(activityId, event.activityId());
    }

    @Test
    void finalSubmissionStoresCommitAndActualSubmissionTimeAndGradingKeepsThatTime() {
        var saved = attach();
        Instant before = Instant.now();
        saved.submit("a".repeat(40));
        studentActivities.save(saved);
        Instant submittedAt = transaction.execute(status -> {
            var row = entityManager.find(StudentActivityEntity.class, saved.getStudentActivityId());
            assertEquals(SubmissionStatus.SUBMITTED, row.getSubmissionStatus());
            assertEquals("a".repeat(40), row.getSubmittedCommitSha());
            assertFalse(row.getGithubSubmission().getSubmittedAt().isBefore(before));
            return row.getGithubSubmission().getSubmittedAt();
        });
        saved.grade("Good work", 95);
        studentActivities.save(saved);
        transaction.executeWithoutResult(status -> assertEquals(submittedAt,
                entityManager.find(StudentActivityEntity.class, saved.getStudentActivityId())
                        .getGithubSubmission().getSubmittedAt()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableCaching
    static class TestConfig {
        @Bean DataSource dataSource() {
            return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource,
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan("com.io.kira.infrastructure.activity.persistence.entity",
                    "com.io.kira.infrastructure.classroom.persistence.entity",
                    "com.io.kira.infrastructure.user.persistence.entity",
                    "com.io.kira.infrastructure.github.persistence.entity");
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    AvailableSettings.BEAN_CONTAINER, new SpringBeanContainer(beanFactory)));
            return factory;
        }
        @Bean @Primary EntityManager entityManager(EntityManagerFactory factory) {
            return SharedEntityManagerCreator.createSharedEntityManager(factory);
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
        @Bean JpaStudentActivityRepository students(EntityManager manager) {
            return new JpaRepositoryFactory(manager).getRepository(JpaStudentActivityRepository.class);
        }
        @Bean JpaActivityRepository activities(EntityManager manager) {
            return new JpaRepositoryFactory(manager).getRepository(JpaActivityRepository.class);
        }
        @Bean JpaUserRepository users(EntityManager manager) {
            return new JpaRepositoryFactory(manager).getRepository(JpaUserRepository.class);
        }
        @Bean JpaGithubSubmissionRepository githubRows(EntityManager manager) {
            return new JpaRepositoryFactory(manager).getRepository(JpaGithubSubmissionRepository.class);
        }
        @Bean StudentActivityAppRepository studentActivities(JpaStudentActivityRepository students,
                JpaUserRepository users, JpaActivityRepository activities) {
            return new StudentActivityAppRepositoryImpl(students, users, activities);
        }
        @Bean ActivityRepositoryRegistration registration(StudentActivityAppRepository students,
                CreateGithubSubmissionUseCase github) {
            return new ActivityRepositoryRegistration(students, github);
        }
        @Bean ActivityGithubSubmissionAppAdapter unsubmitted(JpaStudentActivityRepository students,
                JpaActivityRepository activities) {
            return new ActivityGithubSubmissionAppAdapter(students, activities);
        }
        @Bean GithubSubmissionIntegrationPort githubIntegration() {
            return mock(GithubSubmissionIntegrationPort.class);
        }
        @Bean CreateGithubSubmissionUseCase githubRegistration(JpaGithubSubmissionRepository githubRows,
                JpaStudentActivityRepository students, GithubSubmissionIntegrationPort github) {
            return new GithubSubmissionRegistration(new GithubSubmissionAppRepositoryImpl(githubRows, students,
                    mock(ClassroomRecentActivityCacheVersion.class)), github);
        }
        @Bean("studentActivityCacheKey") StudentActivityCacheKey cacheKey() {
            return new StudentActivityCacheKey();
        }
        @Bean("activityCacheKey") ActivityCacheKey activityCacheKey() {
            return new ActivityCacheKey();
        }
        @Bean CacheManager cacheManager() {
            var manager = new ConcurrentMapCacheManager();
            manager.setAllowNullValues(false);
            return new TransactionAwareCacheManagerProxy(manager);
        }
    }
}
