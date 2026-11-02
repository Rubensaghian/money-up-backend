package com.moneyup.backend;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts", uniqueConstraints = @UniqueConstraint(
        name = "accounts_connection_account_key",
        columnNames = {"plaid_connection_id", "plaid_account_id"}))
@Getter
@Setter
public class Accounts {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "plaid_connection_id", nullable = false)
    private UUID plaidConnectionId;

    @Column(name = "plaid_account_id", nullable = false, columnDefinition = "text")
    private String plaidAccountId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "official_name", columnDefinition = "text")
    private String officialName;

    @Column(columnDefinition = "text")
    private String mask;

    @Column(name = "account_type", columnDefinition = "text")
    private String type;

    @Column(columnDefinition = "text")
    private String subtype;

    @Column(name = "current_balance", columnDefinition = "numeric")
    private BigDecimal currentBalance;

    @Column(name = "available_balance", columnDefinition = "numeric")
    private BigDecimal availableBalance;

    @Column(name = "iso_currency_code", columnDefinition = "text")
    private String isoCurrencyCode;

    @Column(name = "unofficial_currency_code", columnDefinition = "text")
    private String unofficialCurrencyCode;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "last_fetched_at", nullable = false)
    private Instant lastFetchedAt;
}
