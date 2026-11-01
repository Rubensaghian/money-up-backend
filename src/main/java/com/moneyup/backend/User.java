package com.moneyup.backend;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Getter
    @Setter
    private UUID id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Getter
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Getter
    private Instant updatedAt;

    @Column(unique = true,  nullable = false)
    @Getter
    @Setter
    private String username;

    @Column(nullable = false)
    @Getter
    @Setter
    private String passwordHash;

    @Column(nullable = false,  unique = true)
    @Getter
    @Setter
    private String email;


}
