package com.io.kira.adapter.activity.out.persistence.repository;

import com.io.kira.adapter.activity.out.cache.ActivityCacheNames;
import com.io.kira.adapter.activity.out.persistence.mapper.StudentActivityMapper;
import com.io.kira.application.activity.port.out.StudentActivityAppRepository;
import com.io.kira.domain.activity.entity.StudentActivity;
import com.io.kira.domain.activity.valueObject.SubmissionStatus;
import com.io.kira.infrastructure.activity.persistence.entity.ActivityEntity;
import com.io.kira.infrastructure.activity.persistence.entity.StudentActivityEntity;
import com.io.kira.infrastructure.activity.persistence.repository.JpaActivityRepository;
import com.io.kira.infrastructure.activity.persistence.repository.JpaStudentActivityRepository;
import com.io.kira.infrastructure.github.persistence.entity.GithubSubmissionEntity;
import com.io.kira.infrastructure.user.persistence.entity.UserEntity;
import com.io.kira.infrastructure.user.persistence.repository.JpaUserRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@AllArgsConstructor
public class StudentActivityAppRepositoryImpl implements StudentActivityAppRepository {
    private final JpaStudentActivityRepository jpaStudentActivityRepository;
    private final JpaUserRepository jpaUserRepository;
    private final JpaActivityRepository jpaActivityRepository;

    @Override
    public boolean existsSubmission(UUID userId, UUID activityId) {
        return jpaStudentActivityRepository.existsByUserEntity_UserIdAndActivityEntity_ActivityIdAndGithubSubmissionIsNotNull(userId, activityId);
    }

    @Override
    public boolean existsByUserId(UUID userId) {
        return jpaUserRepository.existsById(userId);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = ActivityCacheNames.STUDENT_ACTIVITY,
            key = "@studentActivityCacheKey.byUserIdAndActivityId(#userId, #activityId)",
            unless = "#result == null")
    public Optional<StudentActivity> findByUserIdAndActivityId(UUID userId, UUID activityId) {
        return jpaStudentActivityRepository.findByUserEntity_UserIdAndActivityEntity_ActivityId(userId, activityId)
                .map(StudentActivityMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = ActivityCacheNames.STUDENT_ACTIVITY,
            key = "@studentActivityCacheKey.repositoryUrlByUserIdAndActivityId(#userId, #activityId)",
            unless = "#result == null")
    public Optional<String> findRepositoryUrlByUserIdAndActivityId(UUID userId, UUID activityId) {
        return jpaStudentActivityRepository.findByUserEntity_UserIdAndActivityEntity_ActivityId(userId, activityId)
                .map(StudentActivityEntity::getGithubSubmission)
                .map(GithubSubmissionEntity::getRepositoryUrl);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = ActivityCacheNames.STUDENT_ACTIVITY,
                    key = "@studentActivityCacheKey.byUserIdAndActivityId(#studentActivity.userId, #studentActivity.activityId)"),
            @CacheEvict(value = ActivityCacheNames.STUDENT_ACTIVITY,
                    key = "@studentActivityCacheKey.repositoryUrlByUserIdAndActivityId(#studentActivity.userId, #studentActivity.activityId)"),
            @CacheEvict(value = ActivityCacheNames.ACTIVITY, allEntries = true),
            @CacheEvict(value = ActivityCacheNames.ACTIVITY_INFO, allEntries = true)
    })
    public StudentActivity save(StudentActivity studentActivity) {
        ActivityEntity activityEntity = jpaActivityRepository.findById(studentActivity.getActivityId())
                .orElseThrow(() -> new IllegalArgumentException("Activity not found: " + studentActivity.getActivityId()));
        UserEntity userEntity = jpaUserRepository.findById(studentActivity.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + studentActivity.getUserId()));
        StudentActivityEntity entity = jpaStudentActivityRepository.findById(studentActivity.getStudentActivityId())
                .orElseGet(() -> {
                    StudentActivityEntity created = new StudentActivityEntity();
                    created.setStudentActivityId(studentActivity.getStudentActivityId());
                    return created;
                });

        // Attaching a repository is PENDING. The submission time belongs to the
        // later transition that captures the student's finished commit.
        if (studentActivity.getSubmissionStatus() == SubmissionStatus.SUBMITTED
                && entity.getSubmissionStatus() != SubmissionStatus.SUBMITTED
                && entity.getGithubSubmission() != null) {
            entity.getGithubSubmission().setSubmittedAt(Instant.now());
        }

        entity.setActivityEntity(activityEntity);
        entity.setUserEntity(userEntity);
        entity.setSubmissionStatus(studentActivity.getSubmissionStatus());
        entity.setFeedback(studentActivity.getFeedback());
        entity.setScore(studentActivity.getScore());
        entity.setSubmittedCommitSha(studentActivity.getSubmittedCommitSha());

        StudentActivityEntity savedEntity = jpaStudentActivityRepository.save(entity);
        return StudentActivityMapper.toDomain(savedEntity);
    }
}
