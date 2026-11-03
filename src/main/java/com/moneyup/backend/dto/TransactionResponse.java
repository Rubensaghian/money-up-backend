package com.moneyup.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        BigDecimal amount,
        String currencyCode,
        String merchantName
) {
}