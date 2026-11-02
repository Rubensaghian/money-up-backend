package com.moneyup.backend.service;

import com.moneyup.backend.Accounts;
import com.moneyup.backend.PlaidConnection;
import com.moneyup.backend.Repository.AccountsRepository;
import com.moneyup.backend.Repository.PlaidConnectionRepository;
import com.moneyup.backend.Repository.UserRepository;
import com.moneyup.backend.User;
import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.web.server.ResponseStatusException;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaidServiceTests {
    private PlaidApi api;
    private UserRepository users;
    private PlaidConnectionRepository connections;
    private AccountsRepository accounts;
    private AccountSnapshotService snapshots;
    private TextEncryptor encryptor;
    private PlaidService service;
    private User user;

    @BeforeEach
    void setup() {
        api = mock(PlaidApi.class);
        users = mock(UserRepository.class);
        connections = mock(PlaidConnectionRepository.class);
        accounts = mock(AccountsRepository.class);
        snapshots = mock(AccountSnapshotService.class);
        encryptor = Encryptors.delux("test-secret-used-only-for-unit-tests", "0011223344556677");
        service = new PlaidService(api, users, connections, encryptor, accounts, snapshots);
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
    }

    @Test
    void noConnectionsReturnsAnEmptyListWithoutCallingPlaid() {
        when(connections.findAllByUserId(user.getId())).thenReturn(List.of());

        assertEquals(List.of(), service.getAccounts("alice"));
        verifyNoInteractions(api, accounts, snapshots);
    }

    @Test
    void cachedReadPreservesConnectionsWithTheSameBankNameAndNullBalances() {
        PlaidConnection first = connection("item-one");
        PlaidConnection second = connection("item-two");
        Accounts firstAccount = account(first, "Daily checking");
        Accounts secondAccount = account(second, "Bills checking");
        secondAccount.setCurrentBalance(null);
        secondAccount.setAvailableBalance(null);
        secondAccount.setSubtype(null);
        when(connections.findAllByUserId(user.getId())).thenReturn(List.of(first, second));
        when(accounts.findAllByPlaidConnectionIdInAndActiveTrueOrderByNameAsc(anyCollection()))
                .thenReturn(List.of(firstAccount, secondAccount));

        var result = service.getAccounts("alice");

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(connection -> connection.institutionName().equals("Same bank")));
        assertEquals(2, result.stream().mapToInt(connection -> connection.accounts().size()).sum());
        var missingBalance = result.stream().flatMap(connection -> connection.accounts().stream())
                .filter(account -> account.name().equals("Bills checking")).findFirst().orElseThrow();
        assertNull(missingBalance.currentBalance());
        assertNull(missingBalance.subtype());
        verify(accounts).findAllByPlaidConnectionIdInAndActiveTrueOrderByNameAsc(
                argThat(ids -> ids.size() == 2 && ids.contains(first.getId()) && ids.contains(second.getId())));
        verifyNoInteractions(api, snapshots);
    }

    @Test
    void refreshDecryptsTheTokenAndPassesTheOwnerToSnapshotStorage() throws Exception {
        PlaidConnection connection = connection("item-one");
        when(connections.findAllByUserId(user.getId())).thenReturn(List.of(connection));
        AccountsGetResponse response = new AccountsGetResponse().accounts(List.of());
        Call<AccountsGetResponse> call = success(response);
        when(api.accountsGet(any())).thenReturn(call);

        service.refreshAccounts("alice");

        verify(api).accountsGet(argThat(request -> "access-test".equals(request.getAccessToken())));
        verify(snapshots).saveSnapshot(connection.getId(), user.getId(), response);
    }

    @Test
    void aFailedBankDoesNotPreventAnotherBankFromRefreshing() throws Exception {
        PlaidConnection first = connection("item-one");
        PlaidConnection second = connection("item-two");
        when(connections.findAllByUserId(user.getId())).thenReturn(List.of(first, second));
        Call<AccountsGetResponse> failed = call();
        when(failed.execute()).thenThrow(new IOException("Bank unavailable"));
        AccountsGetResponse response = new AccountsGetResponse().accounts(List.of());
        Call<AccountsGetResponse> healthy = success(response);
        when(api.accountsGet(any())).thenReturn(failed).thenReturn(healthy);

        assertThrows(IOException.class, () -> service.refreshAccounts("alice"));

        verify(snapshots, never()).saveSnapshot(eq(first.getId()), any(), any());
        verify(snapshots).saveSnapshot(second.getId(), user.getId(), response);
        verifyNoInteractions(accounts);
    }

    @Test
    void anEmptyPlaidResponseDoesNotOverwriteTheSavedSnapshot() throws Exception {
        PlaidConnection connection = connection("item-one");
        when(connections.findAllByUserId(user.getId())).thenReturn(List.of(connection));
        Call<AccountsGetResponse> call = success(null);
        when(api.accountsGet(any())).thenReturn(call);

        assertThrows(IOException.class, () -> service.refreshAccounts("alice"));
        verifyNoInteractions(accounts, snapshots);
    }

    @Test
    void tokenExchangeSavesEncryptedCredentialsAndReturnsOnlyTheConnectionId() throws Exception {
        var response = new ItemPublicTokenExchangeResponse().accessToken("access-test").itemId("item-one");
        Call<ItemPublicTokenExchangeResponse> call = success(response);
        when(api.itemPublicTokenExchange(any())).thenReturn(call);
        UUID savedId = UUID.randomUUID();
        when(connections.save(any())).thenAnswer(invocation -> {
            PlaidConnection connection = invocation.getArgument(0);
            assertEquals(user.getId(), connection.getUserId());
            assertEquals("item-one", connection.getItemId());
            assertNotEquals("access-test", connection.getAccessTokenEncrypted());
            assertEquals("access-test", encryptor.decrypt(connection.getAccessTokenEncrypted()));
            connection.setId(savedId);
            return connection;
        });

        assertEquals(savedId.toString(), service.swapPublicToken("public-test", "alice"));
        verifyNoInteractions(accounts, snapshots);
    }

    @Test
    void anUnknownUserIsRejectedBeforeExchangingTheOneTimeToken() {
        assertThrows(ResponseStatusException.class, () -> service.swapPublicToken("public-test", "unknown"));
        verifyNoInteractions(api, connections, accounts, snapshots);
    }

    private PlaidConnection connection(String itemId) {
        PlaidConnection connection = new PlaidConnection();
        connection.setId(UUID.randomUUID());
        connection.setUserId(user.getId());
        connection.setItemId(itemId);
        connection.setInstitutionName("Same bank");
        connection.setAccessTokenEncrypted(encryptor.encrypt("access-test"));
        return connection;
    }

    private Accounts account(PlaidConnection connection, String name) {
        Accounts account = new Accounts();
        account.setId(UUID.randomUUID());
        account.setPlaidConnectionId(connection.getId());
        account.setName(name);
        account.setSubtype("checking");
        account.setCurrentBalance(new BigDecimal("123.45"));
        account.setIsoCurrencyCode("USD");
        return account;
    }

    @SuppressWarnings("unchecked")
    private static <T> Call<T> call() { return mock(Call.class); }

    private static <T> Call<T> success(T body) throws IOException {
        Call<T> call = call();
        when(call.execute()).thenReturn(Response.success(body));
        return call;
    }
}
