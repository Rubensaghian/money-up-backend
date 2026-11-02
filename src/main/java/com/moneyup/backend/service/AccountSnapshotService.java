package com.moneyup.backend.service;

import com.moneyup.backend.Accounts;
import com.moneyup.backend.PlaidConnection;
import com.moneyup.backend.Repository.AccountsRepository;
import com.moneyup.backend.Repository.PlaidConnectionRepository;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountsGetResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AccountSnapshotService {
    private final AccountsRepository accountsRepository;
    private final PlaidConnectionRepository connectionRepository;

    public AccountSnapshotService(
            AccountsRepository accountsRepository,
            PlaidConnectionRepository connectionRepository) {
        this.accountsRepository = accountsRepository;
        this.connectionRepository = connectionRepository;
    }

    // Only database work runs in this transaction; the Plaid request happens beforehand.
    @Transactional
    public void saveSnapshot(UUID connectionId, UUID userId, AccountsGetResponse snapshot) {
        PlaidConnection connection = connectionRepository.findByIdAndUserId(connectionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bank connection not found."));

        List<AccountBase> incomingAccounts = snapshot.getAccounts();
        if (incomingAccounts == null) {
            throw invalidSnapshot();
        }
        Set<String> incomingIds = new HashSet<>();
        for (AccountBase account : incomingAccounts) {
            if (account == null || account.getAccountId() == null || account.getAccountId().isBlank()
                    || !incomingIds.add(account.getAccountId())) {
                throw invalidSnapshot();
            }
        }
        var item = snapshot.getItem();
        if (item != null && item.getItemId() != null && !connection.getItemId().equals(item.getItemId())) {
            throw invalidSnapshot();
        }

        Map<String, Accounts> savedAccounts = new HashMap<>();
        for (Accounts account : accountsRepository.findAllByPlaidConnectionId(connectionId)) {
            savedAccounts.put(account.getPlaidAccountId(), account);
        }

        savedAccounts.values().forEach(account -> account.setActive(false));
        Instant fetchedAt = Instant.now();
        for (AccountBase remote : incomingAccounts) {
            Accounts account = savedAccounts.computeIfAbsent(remote.getAccountId(), accountId -> {
                Accounts created = new Accounts();
                created.setPlaidConnectionId(connectionId);
                created.setPlaidAccountId(accountId);
                return created;
            });
            account.setName(remote.getName() == null || remote.getName().isBlank() ? "Bank account" : remote.getName());
            account.setOfficialName(remote.getOfficialName());
            account.setMask(remote.getMask());
            account.setType(remote.getType() == null ? null : remote.getType().toString());
            account.setSubtype(remote.getSubtype() == null ? null : remote.getSubtype().toString());

            var balances = remote.getBalances();
            account.setCurrentBalance(balances == null ? null : decimal(balances.getCurrent()));
            account.setAvailableBalance(balances == null ? null : decimal(balances.getAvailable()));
            account.setIsoCurrencyCode(balances == null ? null : balances.getIsoCurrencyCode());
            account.setUnofficialCurrencyCode(balances == null ? null : balances.getUnofficialCurrencyCode());
            account.setActive(true);
            account.setLastFetchedAt(fetchedAt);
        }

        if (item != null) {
            if (item.getInstitutionId() != null) connection.setInstitutionId(item.getInstitutionId());
            if (item.getInstitutionName() != null) connection.setInstitutionName(item.getInstitutionName());
        }
        connection.setAccountsLastFetchedAt(fetchedAt);

        accountsRepository.saveAll(savedAccounts.values());
        connectionRepository.save(connection);
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static ResponseStatusException invalidSnapshot() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Plaid returned incomplete account details. Please try again.");
    }
}
