package com.moneyup.backend.Repository;

import com.moneyup.backend.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findByAccountIdAndPlaidTransactionId(UUID accountId, String plaidTransactionId);

    List<Transaction> findAllByAccountId(UUID accountId);

    boolean existsByAccountIdAndPlaidTransactionId(UUID accountId, String plaidTransactionId);

    @Query("""
            select t from Transaction t
            where t.accountId in :accountIds and t.pending = false
              and t.date >= :startDate and t.date < :endDate
            order by t.date desc, t.id
            """)
    List<Transaction> findPostedForBudget(Collection<UUID> accountIds, LocalDate startDate, LocalDate endDate);
}
