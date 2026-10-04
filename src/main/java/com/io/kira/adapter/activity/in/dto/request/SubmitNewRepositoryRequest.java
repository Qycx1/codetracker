package com.io.kira.adapter.activity.in.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SubmitNewRepositoryRequest(
        @NotBlank
        @Pattern(regexp = "(?!\\.{1,2}$)[a-zA-Z0-9._-]{1,100}",
                message = "Repository name must use 1–100 letters, numbers, periods, hyphens, or underscores")
        String repositoryName) {
}
