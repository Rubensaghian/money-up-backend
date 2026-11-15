package com.moneyup.backend.dto;

import java.util.List;

public record BudgetDetailResponse(BudgetResponse budget, List<BudgetTransactionResponse> transactions) {}
