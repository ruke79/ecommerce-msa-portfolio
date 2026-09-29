package com.project.saga_projection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** {@code saga-projection.*} — application-local.yml 참조. */
@ConfigurationProperties(prefix = "saga-projection")
public record SagaProjectionProperties(
        @NotBlank String applicationId,
        @NotBlank @Pattern(regexp = "[^:]+:\\d+", message = "host:port 여야 한다") String applicationServer,
        @NotBlank String stateDir,
        @Min(0) int standbyReplicas,
        Topics topics) {

    public record Topics(@NotBlank String order, @NotBlank String payment, @NotBlank String state) {
    }
}
