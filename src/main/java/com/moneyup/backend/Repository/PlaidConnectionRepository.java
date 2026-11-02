package com.moneyup.backend.Repository;

import com.moneyup.backend.PlaidConnection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlaidConnectionRepository extends JpaRepository<PlaidConnection, UUID> {
    List<PlaidConnection> findAllByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PlaidConnection> findByIdAndUserId(UUID id, UUID userId);
}
