package com.io.kira.application.activity.service;
import com.io.kira.application.activity.error.SubmitExistingRepositoryError;
import com.io.kira.application.activity.error.SubmitNewRepositoryError;
import com.io.kira.application.activity.port.in.SubmitExistingRepositoryUseCase;
import com.io.kira.application.activity.port.in.SubmitNewRepositoryUseCase;
import com.io.kira.application.activity.port.out.ActivityClassroomAppPort;
import com.io.kira.application.activity.port.out.ActivityGithubAccountAppPort;
import com.io.kira.application.activity.port.out.GithubActivityIntegrationPort;
import com.io.kira.application.activity.port.out.StudentActivityAppRepository;
import com.io.kira.application.activity.result.StudentActivitySubmissionData;
import com.io.kira.application.github.error.CreateGithubSubmissionError;
import com.io.kira.common.result.Result;
import com.io.kira.domain.activity.entity.StudentActivity;
import com.io.kira.domain.auth.entity.GithubAccount;
import com.io.kira.domain.github.valueobject.GithubSubmissionMode;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@AllArgsConstructor
@Slf4j
public class SubmitUntrackedRepositoryService implements SubmitNewRepositoryUseCase, SubmitExistingRepositoryUseCase {

    private final StudentActivityAppRepository studentActivityAppRepository;
    private final ActivityClassroomAppPort activityClassroomAppPort;
    private final GithubActivityIntegrationPort githubActivityIntegrationPort;
    private final ActivityGithubAccountAppPort activityGithubAccountAppPort;
    private final ActivityRepositoryRegistration repositoryRegistration;

    @Override
    public Result<StudentActivitySubmissionData, SubmitExistingRepositoryError> submitExisting(UUID authId, UUID userId, UUID classroomId, UUID activityId, String repositoryUrl) {
        if (!activityClassroomAppPort.existsByClassroomId(classroomId))
            return Result.fail(SubmitExistingRepositoryError.CLASSROOM_NOT_FOUND);

        if (!studentActivityAppRepository.existsByUserId(userId))
            return Result.fail(SubmitExistingRepositoryError.USER_NOT_FOUND);

        if (!activityClassroomAppPort.existsByClassroomIdAndActivityId(classroomId, activityId))
            return Result.fail(SubmitExistingRepositoryError.ACTIVITY_NOT_FOUND);

        if (!activityClassroomAppPort.existsByClassroomIdAndStudentUserId(classroomId, userId))
            return Result.fail(SubmitExistingRepositoryError.USER_NOT_CLASSROOM_STUDENT);

        if (studentActivityAppRepository.existsSubmission(userId, activityId))
            return Result.fail(SubmitExistingRepositoryError.ALREADY_SUBMITTED);

        Optional<GithubAccount> githubAccountOptional = activityGithubAccountAppPort.findByAuthId(authId);
        if (githubAccountOptional.isEmpty())
            return Result.fail(SubmitExistingRepositoryError.GITHUB_ACCOUNT_NOT_FOUND);
        var githubAccount = githubAccountOptional.get();

        boolean repositoryExists = githubActivityIntegrationPort.existsByRepository(githubAccount.getAccessToken(), repositoryUrl);

        if (!repositoryExists)
            return Result.fail(SubmitExistingRepositoryError.REPOSITORY_NOT_FOUND);

        try {
            StudentActivity savedStudentActivity = repositoryRegistration.register(
                    githubAccount.getAccessToken(), classroomId, activityId, userId,
                    repositoryUrl, GithubSubmissionMode.EXISTING);
            return Result.ok(StudentActivitySubmissionData.from(savedStudentActivity));
        } catch (ActivityRepositoryRegistration.RegistrationFailedException e) {
            log.warn("Repository attachment failed for activity {} and user {}: {}", activityId, userId, e.error());
            return Result.fail(e.error() == CreateGithubSubmissionError.REPOSITORY_NOT_FOUND
                    ? SubmitExistingRepositoryError.REPOSITORY_NOT_FOUND
                    : SubmitExistingRepositoryError.SAVE_FAILED);
        } catch (RuntimeException e) {
            log.error("Could not attach repository for activity {} and user {}", activityId, userId, e);
            return Result.fail(SubmitExistingRepositoryError.SAVE_FAILED);
        }
    }

    @Override
    public Result<StudentActivitySubmissionData, SubmitNewRepositoryError> submitNew(UUID authId, UUID userId, UUID classroomId, UUID activityId, String repositoryName) {
        if (!activityClassroomAppPort.existsByClassroomId(classroomId))
            return Result.fail(SubmitNewRepositoryError.CLASSROOM_NOT_FOUND);

        if (!studentActivityAppRepository.existsByUserId(userId))
            return Result.fail(SubmitNewRepositoryError.USER_NOT_FOUND);

        if (!activityClassroomAppPort.existsByClassroomIdAndActivityId(classroomId, activityId))
            return Result.fail(SubmitNewRepositoryError.ACTIVITY_NOT_FOUND);

        if (!activityClassroomAppPort.existsByClassroomIdAndStudentUserId(classroomId, userId))
            return Result.fail(SubmitNewRepositoryError.USER_NOT_CLASSROOM_STUDENT);

        if (studentActivityAppRepository.existsSubmission(userId, activityId))
            return Result.fail(SubmitNewRepositoryError.ALREADY_SUBMITTED);

        Optional<GithubAccount> githubAccountOptional = activityGithubAccountAppPort.findByAuthId(authId);
        if (githubAccountOptional.isEmpty())
            return Result.fail(SubmitNewRepositoryError.GITHUB_ACCOUNT_NOT_FOUND);
        var githubAccount = githubAccountOptional.get();

        boolean repositoryExists = githubActivityIntegrationPort.existsByRepositoryName(githubAccount.getAccessToken(), repositoryName);
        if (repositoryExists)
            return Result.fail(SubmitNewRepositoryError.REPOSITORY_ALREADY_EXISTS);

        String createdRepositoryUrl =
                githubActivityIntegrationPort.createRepository(githubAccount.getAccessToken(), repositoryName);

        if (createdRepositoryUrl == null || createdRepositoryUrl.isBlank())
            return Result.fail(SubmitNewRepositoryError.REPOSITORY_CREATE_FAILED);
        try {
            StudentActivity savedStudentActivity = repositoryRegistration.register(
                    githubAccount.getAccessToken(), classroomId, activityId, userId,
                    createdRepositoryUrl, GithubSubmissionMode.NEW);
            return Result.ok(StudentActivitySubmissionData.from(savedStudentActivity));
        } catch (RuntimeException e) {
            // GitHub creation cannot be rolled back by a database transaction.
            // Keep the repository, and let the student attach it with EXISTING.
            log.error("Could not attach newly created repository for activity {} and user {}", activityId, userId, e);
            return Result.fail(SubmitNewRepositoryError.SAVE_FAILED);
        }
    }

}
