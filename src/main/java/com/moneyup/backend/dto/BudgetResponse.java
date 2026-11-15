package com.moneyup.backend.dto;

import com.moneyup.backend.BudgetInterval;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BudgetResponse(
        UUID id, String name, BudgetInterval interval, BigDecimal amount, String currencyCode,
        LocalDate periodStart, LocalDate periodEnd, String timeZone,
        BigDecimal spent, BigDecimal remaining, int excludedTransactionCount,
        List<BudgetAccountResponse> accounts
) {}
