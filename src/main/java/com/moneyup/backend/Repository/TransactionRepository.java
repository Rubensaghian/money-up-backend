package com.moneyup.backend.Repository;

import com.moneyup.backend.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findByAccountIdAndPlaidTransactionId(UUID accountId, String plaidTransactionId);

    List<Transaction> findAllByAccountId(UUID accountId);

    boolean existsByAccountIdAndPlaidTransactionId(UUID accountId, String plaidTransactionId);
}
