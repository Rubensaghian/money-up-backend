package com.moneyup.backend.service;

import com.moneyup.backend.Accounts;
import com.moneyup.backend.PlaidConnection;
import com.moneyup.backend.Repository.AccountsRepository;
import com.moneyup.backend.Repository.PlaidConnectionRepository;
import com.moneyup.backend.Repository.TransactionRepository;
import com.moneyup.backend.Repository.UserRepository;
import com.moneyup.backend.Transaction;
import com.moneyup.backend.User;
import com.moneyup.backend.dto.AccountResponse;
import com.moneyup.backend.dto.AllTransactionResponse;
import com.moneyup.backend.dto.PlaidConnectionResponse;
import com.moneyup.backend.dto.TransactionResponse;
import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import retrofit2.Response;

import java.io.IOException;
import java.util.*;

@Service
public class PlaidService {

    private final PlaidApi plaidClient;
    private final UserRepository userRepository;
    private final PlaidConnectionRepository plaidConnectionRepository;
    private final TextEncryptor tokenEncryptor;
    private final AccountsRepository accountsRepository;
    private final AccountSnapshotService accountSnapshotService;
    private final TransactionRepository transactionRepository;

    public PlaidService(
            PlaidApi plaidClient,
            UserRepository userRepository,
            PlaidConnectionRepository plaidConnectionRepository,
            TextEncryptor tokenEncryptor,
            AccountsRepository accountsRepository,
            AccountSnapshotService accountSnapshotService,
            TransactionRepository transactionRepository) {
        this.plaidClient = plaidClient;
        this.userRepository = userRepository;
        this.plaidConnectionRepository = plaidConnectionRepository;
        this.tokenEncryptor = tokenEncryptor;
        this.accountsRepository = accountsRepository;
        this.accountSnapshotService = accountSnapshotService;
        this.transactionRepository = transactionRepository;
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in again."));
    }

    public String createLinkToken(String username) throws IOException {
        User user = getUser(username);

        LinkTokenCreateRequestUser linkUser = new LinkTokenCreateRequestUser()
                .clientUserId(user.getId().toString());

        LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                .user(linkUser)
                .clientName("MoneyUp")
                .products(List.of(Products.TRANSACTIONS))
                .countryCodes(List.of(CountryCode.US))
                .language("en");

        Response<LinkTokenCreateResponse> response =
                plaidClient.linkTokenCreate(request).execute();

        LinkTokenCreateResponse body = response.body();

        if (!response.isSuccessful() || body == null) {
            throw new IOException("Plaid Token Creation failed with status: " + response.code());
        }
        return body.getLinkToken();
    }

    public String swapPublicToken(String publicToken, String username) throws IOException {
        User user = getUser(username);

        ItemPublicTokenExchangeRequest request = new ItemPublicTokenExchangeRequest()
                .publicToken(publicToken);

        Response<ItemPublicTokenExchangeResponse> response =
                plaidClient.itemPublicTokenExchange(request).execute();

        ItemPublicTokenExchangeResponse body = response.body();

        if (!response.isSuccessful() || body == null) {
            throw new IOException("Plaid Token exchange failed with status: " + response.code());
        }

        String accessToken = body.getAccessToken();
        String itemId = body.getItemId();

        PlaidConnection plaidConnection = new PlaidConnection();
        plaidConnection.setUserId(user.getId());
        plaidConnection.setItemId(itemId);
        plaidConnection.setAccessTokenEncrypted(tokenEncryptor.encrypt(accessToken));


        PlaidConnection saved = plaidConnectionRepository.save(plaidConnection);


        return saved.getId().toString();
    }

    @Transactional(readOnly = true)
    public List<PlaidConnectionResponse> getAccounts(String username) {
        User user = getUser(username);
        List<PlaidConnection> connections = plaidConnectionRepository.findAllByUserId(user.getId());
        if (connections.isEmpty()) return List.of();

        List<UUID> connectionIds = connections.stream().map(PlaidConnection::getId).toList();
        Map<UUID, List<AccountResponse>> accountsByConnection = new HashMap<>();
        for (Accounts account : accountsRepository.findAllByPlaidConnectionIdInAndActiveTrueOrderByNameAsc(connectionIds)) {
            accountsByConnection.computeIfAbsent(account.getPlaidConnectionId(), ignored -> new ArrayList<>())
                    .add(new AccountResponse(
                            account.getId(), account.getName(), account.getOfficialName(), account.getMask(),
                            account.getType(), account.getSubtype(), account.getCurrentBalance(),
                            account.getAvailableBalance(), account.getIsoCurrencyCode() != null
                                    ? account.getIsoCurrencyCode() : account.getUnofficialCurrencyCode()));
        }
        return connections.stream()
                .map(connection -> new PlaidConnectionResponse(
                        connection.getId(), connection.getInstitutionName(), connection.getAccountsLastFetchedAt(),
                        accountsByConnection.getOrDefault(connection.getId(), List.of())))
                .sorted(Comparator.comparing(
                        (PlaidConnectionResponse connection) -> connection.institutionName() == null ? "" : connection.institutionName(),
                        String.CASE_INSENSITIVE_ORDER).thenComparing(connection -> connection.id().toString()))
                .toList();
    }

    public void refreshAccounts(String username) throws IOException {
        User user = getUser(username);
        IOException firstFailure = null;
        for (PlaidConnection connection : plaidConnectionRepository.findAllByUserId(user.getId())) {
            try {
                AccountsGetRequest request = new AccountsGetRequest()
                        .accessToken(tokenEncryptor.decrypt(connection.getAccessTokenEncrypted()));
                Response<AccountsGetResponse> response = plaidClient.accountsGet(request).execute();
                AccountsGetResponse body = response.body();
                if (!response.isSuccessful() || body == null) {
                    throw new IOException("Plaid account request failed with status: " + response.code());
                }
                accountSnapshotService.saveSnapshot(connection.getId(), user.getId(), body);
            } catch (IOException exception) {
                if (firstFailure == null) firstFailure = exception;
            }
        }
        if (firstFailure != null) throw firstFailure;
    }


    @Transactional(readOnly = true)
    public List<AllTransactionResponse> getTransactions(String username) {
        User user = getUser(username);

        List<PlaidConnection> connections = plaidConnectionRepository.findAllByUserId(user.getId());

        if (connections.isEmpty()) return List.of();

        List<UUID> connectionIds = connections.stream().map(PlaidConnection::getId).toList();

        List<AllTransactionResponse> allTransactions = new ArrayList<>();

        for (Accounts accounts : accountsRepository.findAllByPlaidConnectionIdInAndActiveTrueOrderByNameAsc(connectionIds)) {
            List<TransactionResponse> transactions = new ArrayList<>();
            for (Transaction transaction : transactionRepository.findAllByAccountId(accounts.getId())) {
                TransactionResponse transactionResponse = new TransactionResponse(
                        transaction.getId(), transaction.getAmount(), transaction.getIsoCurrencyCode() != null ?
                        transaction.getIsoCurrencyCode() : transaction.getUnofficialCurrencyCode(), transaction.getMerchantName());
                transactions.add(transactionResponse);
            }
            AllTransactionResponse allTransactionResponse = new AllTransactionResponse(
                    accounts.getId(), accounts.getName(), accounts.getSubtype(), transactions
            );

            allTransactions.add(allTransactionResponse);
        }

        return allTransactions;
    }
}
