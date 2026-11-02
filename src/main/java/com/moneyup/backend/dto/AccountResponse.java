package com.moneyup.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        String officialName,
        String mask,
        String type,
        String subtype,
        BigDecimal currentBalance,
        BigDecimal availableBalance,
        String currencyCode) {
}
