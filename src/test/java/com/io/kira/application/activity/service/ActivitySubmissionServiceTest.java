package com.io.kira.application.activity.service;

import com.io.kira.adapter.activity.in.mapper.SubmitActivityHttpMapper;
import com.io.kira.adapter.activity.in.mapper.SubmitExistingRepositoryHttpMapper;
import com.io.kira.application.activity.error.*;
import com.io.kira.application.activity.port.out.*;
import com.io.kira.application.github.error.CreateGithubSubmissionError;
import com.io.kira.domain.activity.entity.StudentActivity;
import com.io.kira.domain.activity.valueObject.SubmissionStatus;
import com.io.kira.domain.auth.entity.GithubAccount;
import com.io.kira.domain.github.valueobject.GithubSubmissionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ActivitySubmissionServiceTest {
    private final UUID authId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID classroomId = UUID.randomUUID();
    private final UUID activityId = UUID.randomUUID();
    private final String repositoryUrl = "https://github.com/student/assignment";
    private StudentActivityAppRepository students;
    private ActivityClassroomAppPort classrooms;
    private ActivityGithubAccountAppPort accounts;
    private GithubActivityIntegrationPort github;
    private ActivityRepositoryRegistration registration;
    private SubmitUntrackedRepositoryService attachments;
    private SubmitTrackedActivityService submissions;
    private StudentActivity pending;

    @BeforeEach
    void setUp() {
        students = mock(StudentActivityAppRepository.class);
        classrooms = mock(ActivityClassroomAppPort.class);
        accounts = mock(ActivityGithubAccountAppPort.class);
        github = mock(GithubActivityIntegrationPort.class);
        registration = mock(ActivityRepositoryRegistration.class);
        attachments = new SubmitUntrackedRepositoryService(students, classrooms, github, accounts, registration);
        submissions = new SubmitTrackedActivityService(students, classrooms, accounts, github);
        pending = StudentActivity.createNew(activityId, userId);
        when(classrooms.existsByClassroomId(classroomId)).thenReturn(true);
        when(classrooms.existsByClassroomIdAndActivityId(classroomId, activityId)).thenReturn(true);
        when(classrooms.existsByClassroomIdAndStudentUserId(classroomId, userId)).thenReturn(true);
        when(students.existsByUserId(userId)).thenReturn(true);
        when(students.findByUserIdAndActivityId(userId, activityId)).thenReturn(Optional.of(pending));
        when(students.findRepositoryUrlByUserIdAndActivityId(userId, activityId)).thenReturn(Optional.of(repositoryUrl));
        when(accounts.findByAuthId(authId)).thenReturn(Optional.of(new GithubAccount(authId, 123L, "test-token")));
        when(github.existsByRepository("test-token", repositoryUrl)).thenReturn(true);
        when(students.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void existingRepositoryAttachmentUsesAtomicRegistrationAndStaysPending() {
        when(registration.register("test-token", classroomId, activityId, userId, repositoryUrl,
                GithubSubmissionMode.EXISTING)).thenReturn(pending);
        var result = attachments.submitExisting(authId, userId, classroomId, activityId, repositoryUrl);
        assertTrue(result.success());
        assertEquals(SubmissionStatus.PENDING, result.data().submissionStatus());
        verify(students, never()).save(any());
    }

    @Test
    void newRepositoryCreationAlsoUsesAtomicRegistration() {
        when(github.createRepository("test-token", "assignment")).thenReturn(repositoryUrl);
        when(registration.register("test-token", classroomId, activityId, userId, repositoryUrl,
                GithubSubmissionMode.NEW)).thenReturn(pending);
        assertTrue(attachments.submitNew(authId, userId, classroomId, activityId, "assignment").success());
        verify(registration).register("test-token", classroomId, activityId, userId, repositoryUrl, GithubSubmissionMode.NEW);
    }

    @Test
    void missingRepositoryIsReportedInsteadOfPretendingThatTheDatabaseFailed() {
        when(registration.register("test-token", classroomId, activityId, userId, repositoryUrl,
                GithubSubmissionMode.EXISTING)).thenThrow(new ActivityRepositoryRegistration.RegistrationFailedException(
                        CreateGithubSubmissionError.REPOSITORY_NOT_FOUND));
        assertEquals(SubmitExistingRepositoryError.REPOSITORY_NOT_FOUND,
                attachments.submitExisting(authId, userId, classroomId, activityId, repositoryUrl).error());
    }

    @Test
    void finalSubmissionCapturesTheLatestDefaultBranchCommit() {
        String sha = "b".repeat(40);
        when(github.findLatestCommitSha("test-token", repositoryUrl)).thenReturn(Optional.of(sha));
        var result = submissions.submit(authId, userId, classroomId, activityId);
        assertTrue(result.success());
        assertEquals(SubmissionStatus.SUBMITTED, result.data().submissionStatus());
        assertEquals(sha, result.data().submittedCommitSha());
    }

    @Test
    void repositoryWithoutACommitStaysPending() {
        when(github.findLatestCommitSha("test-token", repositoryUrl)).thenReturn(Optional.empty());
        assertEquals(SubmitActivityError.COMMIT_NOT_FOUND,
                submissions.submit(authId, userId, classroomId, activityId).error());
        assertEquals(SubmissionStatus.PENDING, pending.getSubmissionStatus());
        verify(students, never()).save(any());
    }

    @Test
    void missingGithubConnectionHasAnActionableError() {
        when(accounts.findByAuthId(authId)).thenReturn(Optional.empty());
        assertEquals(SubmitActivityError.GITHUB_ACCOUNT_NOT_FOUND,
                submissions.submit(authId, userId, classroomId, activityId).error());
        assertEquals(HttpStatus.BAD_REQUEST,
                SubmitActivityHttpMapper.toStatus(SubmitActivityError.GITHUB_ACCOUNT_NOT_FOUND));
    }

    @Test
    void gradedWorkCannotBeSubmittedAgainAndDoesNotCallGithub() {
        pending.submit("a".repeat(40));
        pending.grade("Good work", 95);
        assertEquals(SubmitActivityError.ALREADY_SUBMITTED,
                submissions.submit(authId, userId, classroomId, activityId).error());
        verifyNoInteractions(github);
    }

    @Test
    void classroomPermissionFailureDoesNotMasqueradeAsAnExpiredLogin() {
        when(classrooms.existsByClassroomIdAndStudentUserId(classroomId, userId)).thenReturn(false);
        assertEquals(SubmitExistingRepositoryError.USER_NOT_CLASSROOM_STUDENT,
                attachments.submitExisting(authId, userId, classroomId, activityId, repositoryUrl).error());
        assertEquals(HttpStatus.FORBIDDEN,
                SubmitExistingRepositoryHttpMapper.toStatus(SubmitExistingRepositoryError.USER_NOT_CLASSROOM_STUDENT));
        verifyNoInteractions(github);
    }
}
