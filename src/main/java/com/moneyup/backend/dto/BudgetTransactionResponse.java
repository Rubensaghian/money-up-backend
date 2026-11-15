package com.moneyup.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BudgetTransactionResponse(
        UUID id, UUID accountId, String accountName, String merchantName, LocalDate date,
        BigDecimal amount, String currencyCode, boolean excluded, String treatment
) {}
