package com.moneyup.backend.dto;

import com.moneyup.backend.BudgetInterval;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record BudgetRequest(
        @NotBlank(message = "Enter a budget name.")
        @Size(max = 120, message = "Budget names must be 120 characters or fewer.") String name,
        @NotNull(message = "Choose a budget interval.") BudgetInterval interval,
        @NotNull(message = "Enter a spending limit.")
        @DecimalMin(value = "0", inclusive = false, message = "The spending limit must be greater than zero.")
        @Digits(integer = 12, fraction = 4, message = "Use up to 12 digits and 4 decimal places for the limit.") BigDecimal amount,
        @NotBlank(message = "Enter a currency code.")
        @Pattern(regexp = "[A-Za-z]{3}", message = "Use a three-letter currency code, such as USD.") String currencyCode,
        @NotEmpty(message = "Select at least one account.")
        @Size(max = 100, message = "Select up to 100 accounts.") Set<@NotNull UUID> accountIds
) {}
