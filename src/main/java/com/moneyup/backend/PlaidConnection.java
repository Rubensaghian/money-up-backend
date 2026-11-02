package com.moneyup.backend;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "plaid_connection")
public class PlaidConnection {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Getter
    @Setter
    private UUID id;

    @Getter
    @Setter
    @Column(name = "user_id",  nullable = false)
    private UUID userId;

    @Getter
    @Setter
    @Column(name = "item_id", nullable = false)
    private String itemId;

    @Getter
    @Setter
    @Column(name = "access_token_encrypted", nullable = false)
    private String accessTokenEncrypted;
}
