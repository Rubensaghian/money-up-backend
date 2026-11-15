package com.moneyup.backend;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "budget")
@Getter
@Setter
public class Budget {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "\"interval\"", nullable = false, columnDefinition = "text")
    private BudgetInterval interval;

    @Column(nullable = false, columnDefinition = "numeric")
    private BigDecimal amount;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "currency_code", nullable = false, columnDefinition = "text")
    private String currencyCode;

    @ElementCollection
    @CollectionTable(name = "budget_accounts", joinColumns = @JoinColumn(name = "budget_id"))
    @Column(name = "account_id", nullable = false)
    // Each selected ID becomes a row in the existing budget_accounts table.
    private Set<UUID> accountIds = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "budget_excluded_transactions", joinColumns = @JoinColumn(name = "budget_id"))
    @Column(name = "transaction_id", nullable = false)
    // Exclusions belong to this budget; the saved bank transaction remains intact.
    private Set<UUID> excludedTransactionIds = new HashSet<>();
}
