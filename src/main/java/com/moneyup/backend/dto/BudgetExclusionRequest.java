package com.moneyup.backend.dto;

import jakarta.validation.constraints.NotNull;

public record BudgetExclusionRequest(@NotNull(message = "Specify whether to hide this transaction.") Boolean excluded) {}
