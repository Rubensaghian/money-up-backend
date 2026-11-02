package com.moneyup.backend.Repository;

import com.moneyup.backend.PlaidConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlaidConnectionRepository extends JpaRepository<PlaidConnection, UUID> {
    List<PlaidConnection> findAllByUserId(UUID userId);
}
