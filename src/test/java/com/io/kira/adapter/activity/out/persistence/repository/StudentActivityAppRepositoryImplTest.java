package com.io.kira.adapter.activity.out.persistence.repository;

import com.io.kira.domain.activity.entity.StudentActivity;
import com.io.kira.domain.activity.valueObject.SubmissionStatus;
import com.io.kira.infrastructure.activity.persistence.entity.ActivityEntity;
import com.io.kira.infrastructure.activity.persistence.entity.StudentActivityEntity;
import com.io.kira.infrastructure.activity.persistence.repository.JpaActivityRepository;
import com.io.kira.infrastructure.activity.persistence.repository.JpaStudentActivityRepository;
import com.io.kira.infrastructure.user.persistence.entity.UserEntity;
import com.io.kira.infrastructure.user.persistence.repository.JpaUserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StudentActivityAppRepositoryImplTest {
    @Test
    void savesFirstRepositoryAttachmentWithoutAnExistingStudentActivityRow() {
        var students = mock(JpaStudentActivityRepository.class);
        var users = mock(JpaUserRepository.class);
        var activities = mock(JpaActivityRepository.class);
        var repository = new StudentActivityAppRepositoryImpl(students, users, activities);
        var activity = new ActivityEntity();
        activity.setActivityId(UUID.randomUUID());
        var user = new UserEntity();
        user.setUserId(UUID.randomUUID());
        var submission = StudentActivity.createNew(activity.getActivityId(), user.getUserId());
        when(activities.findById(activity.getActivityId())).thenReturn(Optional.of(activity));
        when(users.findById(user.getUserId())).thenReturn(Optional.of(user));
        when(students.findById(submission.getStudentActivityId())).thenReturn(Optional.empty());
        when(students.save(any(StudentActivityEntity.class))).thenAnswer(call -> call.getArgument(0));

        var saved = repository.save(submission);

        assertEquals(submission.getStudentActivityId(), saved.getStudentActivityId());
        assertEquals(SubmissionStatus.PENDING, saved.getSubmissionStatus());
        verify(students).save(argThat(entity ->
                entity.getActivityEntity() == activity && entity.getUserEntity() == user));
    }
}
