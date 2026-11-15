package com.moneyup.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record ConnectionSetupResponse(
        @JsonProperty("connection_id") UUID connectionId,
        boolean initialized) {
}
