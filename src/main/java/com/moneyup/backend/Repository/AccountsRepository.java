package com.moneyup.backend.Repository;

import com.moneyup.backend.Accounts;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AccountsRepository extends JpaRepository<Accounts, UUID> {
    List<Accounts> findAllByPlaidConnectionId(UUID plaidConnectionId);

    List<Accounts> findAllByPlaidConnectionIdInAndActiveTrueOrderByNameAsc(Collection<UUID> connectionIds);
}
