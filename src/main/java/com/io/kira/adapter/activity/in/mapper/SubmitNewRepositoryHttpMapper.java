package com.io.kira.adapter.activity.in.mapper;

import com.io.kira.application.activity.error.SubmitNewRepositoryError;
import org.springframework.http.HttpStatus;

public final class SubmitNewRepositoryHttpMapper {

    private SubmitNewRepositoryHttpMapper() {}

    public static HttpStatus toStatus(SubmitNewRepositoryError error) {
        return switch (error) {
            case USER_NOT_FOUND,
                 ACTIVITY_NOT_FOUND,
                 GITHUB_ACCOUNT_NOT_FOUND,
                 CLASSROOM_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case USER_NOT_CLASSROOM_STUDENT -> HttpStatus.FORBIDDEN;
            case ALREADY_SUBMITTED,
                 REPOSITORY_ALREADY_EXISTS -> HttpStatus.CONFLICT;
            case REPOSITORY_CREATE_FAILED,
                 SAVE_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    public static String toMessage(SubmitNewRepositoryError error) {
        return switch (error) {
            case USER_NOT_FOUND -> "User not found";
            case USER_NOT_CLASSROOM_STUDENT -> "User is not an active student of this classroom";
            case ACTIVITY_NOT_FOUND -> "Activity not found in this classroom";
            case ALREADY_SUBMITTED -> "A repository is already attached. Find this activity in Tracked Activities";
            case GITHUB_ACCOUNT_NOT_FOUND -> "Github account not found";
            case REPOSITORY_ALREADY_EXISTS -> "This repository already exists. Choose Use existing repository and select it";
            case REPOSITORY_CREATE_FAILED -> "Could not create the repository. Check its name and reconnect your GitHub account, then try again";
            case SAVE_FAILED -> "Could not attach the new repository. It may already exist on GitHub. Choose Use existing repository and select it to try again";
            case CLASSROOM_NOT_FOUND -> "Classroom not found";
        };
    }
}
