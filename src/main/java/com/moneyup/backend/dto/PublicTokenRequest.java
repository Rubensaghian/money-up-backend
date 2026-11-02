package com.moneyup.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record PublicTokenRequest(
        @JsonProperty("public_token")
        @NotBlank(message = "A public token is required.") String publicToken) {
}
