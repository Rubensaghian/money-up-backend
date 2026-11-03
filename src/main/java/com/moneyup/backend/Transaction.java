package com.moneyup.backend;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "transaction")
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Getter
    private UUID id;

    @Column(name = "account_id", nullable = false)
    @Getter
    @Setter
    private UUID accountId;

    @Column(name = "amount", nullable = false)
    @Getter
    @Setter
    private BigDecimal amount;

    @Column(name = "iso_currency_code", columnDefinition = "text")
    @Getter
    @Setter
    private String isoCurrencyCode;

    @Column(name = "unofficial_currency_code", columnDefinition = "text")
    @Getter
    @Setter
    private String unofficialCurrencyCode;

    @Column(name = "datetime")
    @Getter
    @Setter
    private Instant datetime;

    @Column(name = "date",  nullable = false)
    @Getter
    @Setter
    private LocalDate date;

    @Column(name = "pending",  nullable = false)
    @Getter
    @Setter
    private Boolean pending;

    @Column(name = "pending_transaction_id")
    @Getter
    @Setter
    private String pendingTransactionId;

    @Column(name = "plaid_transaction_id")
    @Getter
    @Setter
    private String plaidTransactionId;

    @Column(name = "merchant_name")
    @Getter
    @Setter
    private String merchantName;

}
