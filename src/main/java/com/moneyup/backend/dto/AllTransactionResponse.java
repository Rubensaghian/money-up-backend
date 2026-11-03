package com.moneyup.backend.dto;

import java.util.List;
import java.util.UUID;

public record AllTransactionResponse(
        UUID accountId,
        String name,
        String subtype,
        List<TransactionResponse> transactions
) {
}
