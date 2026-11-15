package com.moneyup.backend.dto;

import java.util.UUID;

public record BudgetAccountResponse(
        UUID id, String name, String institutionName, String mask, String currencyCode, boolean active
) {}
