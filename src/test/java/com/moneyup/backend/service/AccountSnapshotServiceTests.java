package com.moneyup.backend.service;

import com.moneyup.backend.Accounts;
import com.moneyup.backend.PlaidConnection;
import com.moneyup.backend.Repository.AccountsRepository;
import com.moneyup.backend.Repository.PlaidConnectionRepository;
import com.plaid.client.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountSnapshotServiceTests {
    private final UUID userId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private AccountsRepository accounts;
    private PlaidConnectionRepository connections;
    private AccountSnapshotService service;
    private PlaidConnection connection;
    private List<Accounts> saved;

    @BeforeEach
    void setup() {
        accounts = mock(AccountsRepository.class);
        connections = mock(PlaidConnectionRepository.class);
        service = new AccountSnapshotService(accounts, connections);
        connection = new PlaidConnection();
        connection.setId(connectionId);
        connection.setUserId(userId);
        connection.setItemId("item-one");
        when(connections.findByIdAndUserId(connectionId, userId)).thenReturn(Optional.of(connection));
        when(accounts.findAllByPlaidConnectionId(connectionId)).thenReturn(List.of());
        when(accounts.saveAll(any())).thenAnswer(invocation -> {
            saved = new ArrayList<>();
            Iterable<Accounts> rows = invocation.getArgument(0);
            rows.forEach(account -> {
                if (account.getId() == null) account.setId(UUID.randomUUID());
                saved.add(account);
            });
            return saved;
        });
    }

    @Test
    void savesTwoCheckingAccountsWithoutCombiningThem() {
        service.saveSnapshot(connectionId, userId, snapshot(List.of(
                remote("account-one", "Daily checking", 123.45),
                remote("account-two", "Bills checking", 678.90))));

        assertEquals(2, saved.size());
        assertEquals(List.of("Bills checking", "Daily checking"), saved.stream().map(Accounts::getName).sorted().toList());
        assertTrue(saved.stream().allMatch(account -> connectionId.equals(account.getPlaidConnectionId())));
        assertEquals("Demo bank", connection.getInstitutionName());
        assertEquals("institution-one", connection.getInstitutionId());
        assertNotNull(connection.getAccountsLastFetchedAt());
        verify(connections).save(connection);
    }

    @Test
    void refreshUpdatesTheSameDatabaseRow() {
        Accounts existing = stored("account-one");
        UUID originalId = existing.getId();
        when(accounts.findAllByPlaidConnectionId(connectionId)).thenReturn(List.of(existing));

        service.saveSnapshot(connectionId, userId, snapshot(List.of(remote("account-one", "Renamed checking", 42.50))));

        assertEquals(1, saved.size());
        assertEquals(originalId, saved.getFirst().getId());
        assertEquals("Renamed checking", saved.getFirst().getName());
        assertEquals(new BigDecimal("42.5"), saved.getFirst().getCurrentBalance());
        assertTrue(saved.getFirst().isActive());
    }

    @Test
    void missingBalancesAndSubtypesDoNotHideAccounts() {
        AccountBase unknownBalance = remote("account-one", "Savings", null).subtype(null).balances(null);
        AccountBase availableOnly = remote("account-two", "Checking", null)
                .balances(new AccountBalance().current(null).available(25.0).isoCurrencyCode("USD"));

        service.saveSnapshot(connectionId, userId, snapshot(List.of(unknownBalance, availableOnly)));

        assertEquals(2, saved.size());
        Accounts first = saved.stream().filter(account -> account.getPlaidAccountId().equals("account-one")).findFirst().orElseThrow();
        assertNull(first.getCurrentBalance());
        assertNull(first.getSubtype());
        assertTrue(first.isActive());
        Accounts second = saved.stream().filter(account -> account.getPlaidAccountId().equals("account-two")).findFirst().orElseThrow();
        assertNull(second.getCurrentBalance());
        assertEquals(new BigDecimal("25.0"), second.getAvailableBalance());
    }

    @Test
    void missingAccountsAreMarkedInactiveWithoutDeletingHistory() {
        Accounts retained = stored("account-one");
        Accounts missing = stored("account-two");
        Instant lastSeen = missing.getLastFetchedAt();
        when(accounts.findAllByPlaidConnectionId(connectionId)).thenReturn(List.of(retained, missing));

        service.saveSnapshot(connectionId, userId, snapshot(List.of(remote("account-one", "Checking", 10.0))));

        assertEquals(2, saved.size());
        assertTrue(retained.isActive());
        assertFalse(missing.isActive());
        assertEquals(lastSeen, missing.getLastFetchedAt());
    }

    @Test
    void anEmptySuccessfulSnapshotMarksPreviousAccountsInactive() {
        Accounts existing = stored("account-one");
        when(accounts.findAllByPlaidConnectionId(connectionId)).thenReturn(List.of(existing));

        service.saveSnapshot(connectionId, userId, snapshot(List.of()));

        assertFalse(existing.isActive());
        assertNotNull(connection.getAccountsLastFetchedAt());
    }

    @Test
    void malformedSnapshotDoesNotOverwriteSavedAccounts() {
        var exception = assertThrows(ResponseStatusException.class,
                () -> service.saveSnapshot(connectionId, userId, new AccountsGetResponse().accounts(null)));
        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        verify(accounts, never()).saveAll(any());
        verify(connections, never()).save(any());
    }

    @Test
    void rejectsAResponseForAnotherItem() {
        AccountsGetResponse response = snapshot(List.of(remote("account-one", "Checking", 10.0)));
        response.getItem().setItemId("another-item");

        assertThrows(ResponseStatusException.class, () -> service.saveSnapshot(connectionId, userId, response));
        verify(accounts, never()).saveAll(any());
        verify(connections, never()).save(any());
    }

    @Test
    void anotherUserCannotUpdateThisConnection() {
        UUID otherUser = UUID.randomUUID();
        var exception = assertThrows(ResponseStatusException.class,
                () -> service.saveSnapshot(connectionId, otherUser, snapshot(List.of())));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(accounts);
    }

    @Test
    void duplicateAccountIdsAreRejectedBeforeWriting() {
        AccountsGetResponse response = snapshot(List.of(
                remote("account-one", "Checking", 10.0), remote("account-one", "Duplicate", 20.0)));

        assertThrows(ResponseStatusException.class, () -> service.saveSnapshot(connectionId, userId, response));
        verify(accounts, never()).saveAll(any());
        verify(connections, never()).save(any());
    }

    private Accounts stored(String plaidId) {
        Accounts account = new Accounts();
        account.setId(UUID.randomUUID());
        account.setPlaidConnectionId(connectionId);
        account.setPlaidAccountId(plaidId);
        account.setName("Checking");
        account.setLastFetchedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return account;
    }

    private static AccountBase remote(String id, String name, Double balance) {
        return new AccountBase().accountId(id).name(name).mask("1234")
                .type(AccountType.DEPOSITORY).subtype(AccountSubtype.CHECKING)
                .balances(new AccountBalance().current(balance).available(balance).isoCurrencyCode("USD"));
    }

    private static AccountsGetResponse snapshot(List<AccountBase> accounts) {
        return new AccountsGetResponse().accounts(accounts)
                .item(new Item().itemId("item-one").institutionId("institution-one").institutionName("Demo bank"));
    }
}
