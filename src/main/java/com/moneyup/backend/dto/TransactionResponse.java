package com.moneyup.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        BigDecimal amount,
        String currencyCode,
        String merchantName,
        LocalDate date,
        Boolean pending
) {
}