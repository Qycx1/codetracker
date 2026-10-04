package com.io.kira.application.activity.service;

import com.io.kira.application.activity.port.out.StudentActivityAppRepository;
import com.io.kira.application.github.command.CreateGithubSubmissionCommand;
import com.io.kira.application.github.error.CreateGithubSubmissionError;
import com.io.kira.application.github.port.in.CreateGithubSubmissionUseCase;
import com.io.kira.domain.activity.entity.StudentActivity;
import com.io.kira.domain.activity.valueObject.SubmissionStatus;
import com.io.kira.domain.github.valueobject.GithubSubmissionMode;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Saves both halves of the attachment together, or rolls back both. */
@Service
@AllArgsConstructor
public class ActivityRepositoryRegistration {
    private final StudentActivityAppRepository studentActivities;
    private final CreateGithubSubmissionUseCase githubSubmissions;

    @Transactional
    public StudentActivity register(String accessToken, UUID classroomId, UUID activityId,
                                    UUID userId, String repositoryUrl, GithubSubmissionMode mode) {
        // Reuse a PENDING row left by the old non-transactional implementation.
        StudentActivity studentActivity = studentActivities.findByUserIdAndActivityId(userId, activityId)
                .orElseGet(() -> StudentActivity.createNew(activityId, userId));
        if (studentActivity.getSubmissionStatus() != SubmissionStatus.PENDING) {
            throw new IllegalStateException("Only pending work can attach a repository");
        }
        StudentActivity saved = studentActivities.save(studentActivity);
        var result = githubSubmissions.execute(new CreateGithubSubmissionCommand(
                accessToken, classroomId, saved.getStudentActivityId(), activityId, repositoryUrl, mode));
        if (!result.success()) {
            // Returning Result.fail here would still commit the first insert.
            throw new RegistrationFailedException(result.error());
        }
        return saved;
    }

    static final class RegistrationFailedException extends RuntimeException {
        private final CreateGithubSubmissionError error;

        RegistrationFailedException(CreateGithubSubmissionError error) {
            super("Could not register activity repository: " + error);
            this.error = error;
        }

        CreateGithubSubmissionError error() {
            return error;
        }
    }
}
