package com.moneyup.backend.Repository;

import com.moneyup.backend.Budget;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {
    List<Budget> findAllByUserIdOrderByNameAsc(UUID userId);

    Optional<Budget> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Budget b where b.id = :id and b.userId = :userId")
    Optional<Budget> findOwnedForUpdate(UUID id, UUID userId);
}
