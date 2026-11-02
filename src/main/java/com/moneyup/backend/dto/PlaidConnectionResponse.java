package com.moneyup.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlaidConnectionResponse(
        UUID id,
        String institutionName,
        Instant accountsLastFetchedAt,
        List<AccountResponse> accounts) {
}
