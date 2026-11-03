package com.moneyup.backend.service;

import com.moneyup.backend.Accounts;
import com.moneyup.backend.PlaidConnection;
import com.moneyup.backend.Repository.AccountsRepository;
import com.moneyup.backend.Repository.PlaidConnectionRepository;
import com.moneyup.backend.Repository.TransactionRepository;
import com.moneyup.backend.Repository.UserRepository;
import com.moneyup.backend.User;
import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.request.PlaidApi;
import lombok.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionSyncService {

    private final PlaidApi plaidClient;
    private final UserRepository userRepository;
    private final PlaidConnectionRepository plaidConnectionRepository;
    private final TextEncryptor tokenEncryptor;
    private final TransactionRepository transactionRepository;
    private final AccountsRepository accountsRepository;
    private final TransactionSaveService transactionSaveService;

    public TransactionSyncService(PlaidApi plaidClient,
                                  UserRepository userRepository,
                                  PlaidConnectionRepository plaidConnectionRepository,
                                  TextEncryptor tokenEncryptor,
                                  TransactionRepository transactionRepository,
                                  AccountsRepository accountsRepository, TransactionSaveService transactionSaveService) {
        this.plaidClient = plaidClient;
        this.userRepository = userRepository;
        this.plaidConnectionRepository = plaidConnectionRepository;
        this.tokenEncryptor = tokenEncryptor;
        this.transactionRepository = transactionRepository;
        this.accountsRepository = accountsRepository;
        this.transactionSaveService = transactionSaveService;
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in again."));
    }

    public void syncTransactions(String username) throws IOException {
        User user = getUser(username);

        for (PlaidConnection connection : plaidConnectionRepository.findAllByUserId(user.getId())) {
            String cursor = connection.getCursor();
            String accessTokenEncrypted = connection.getAccessTokenEncrypted();

            TransactionsSyncRequest request = new TransactionsSyncRequest()
                    .accessToken(tokenEncryptor.decrypt(accessTokenEncrypted));

            if (cursor != null && !cursor.isEmpty()) {
                request.setCursor(cursor);
            }

            Response<TransactionsSyncResponse> response = plaidClient.transactionsSync(request).execute();
            TransactionsSyncResponse body = response.body();

            List<com.moneyup.backend.Transaction> transactionsToUpdate = new ArrayList<>();
            List<com.moneyup.backend.Transaction> transactionsToDelete = new ArrayList<>();

            if (!response.isSuccessful() || body == null) {
                throw new IOException("Plaid transactions sync request failed with status: " + response.code());
            }

            handleUpdateTransactions(body.getAdded(), connection, transactionsToUpdate);
            handleUpdateTransactions(body.getModified(), connection, transactionsToUpdate);
            handleDeletedTransactions(body.getRemoved(), connection, transactionsToDelete);

            while (body.getHasMore()) {
                request.setCursor(body.getNextCursor());

                response = plaidClient.transactionsSync(request).execute();
                body = response.body();

                if (!response.isSuccessful() || body == null) {
                    throw new IOException("Plaid transactions sync request failed with status: " + response.code());
                }

                handleUpdateTransactions(body.getAdded(), connection, transactionsToUpdate);
                handleUpdateTransactions(body.getModified(), connection, transactionsToUpdate);
                handleDeletedTransactions(body.getRemoved(), connection, transactionsToDelete);
            }

            transactionSaveService.saveTransaction(
                    transactionsToUpdate, transactionsToDelete, body.getNextCursor(), connection);
        }
    }

    public void handleUpdateTransactions(@NonNull List<Transaction> update, PlaidConnection connection,
                                        List<com.moneyup.backend.Transaction> transactionsToUpdate) throws IOException {
        if (update.isEmpty()) return;

        for (Transaction transaction : update) {
            String plaidAccountId = transaction.getAccountId();
            Accounts accounts =  accountsRepository.findByPlaidAccountIdAndPlaidConnectionId(plaidAccountId, connection.getId())
                    .orElseThrow(() -> new IOException("Plaid account not found: " + plaidAccountId));
            UUID accountId = accounts.getId();
            String plaidTransactionId = transaction.getTransactionId();

            com.moneyup.backend.Transaction transactionToChange = transactionRepository.findByAccountIdAndPlaidTransactionId(accountId, plaidTransactionId)
                    .orElse(new com.moneyup.backend.Transaction());

            transactionToChange.setAccountId(accountId);
            transactionToChange.setPlaidTransactionId(plaidTransactionId);
            transactionToChange.setAmount(decimal(transaction.getAmount()));
            transactionToChange.setIsoCurrencyCode(transaction.getIsoCurrencyCode());
            transactionToChange.setUnofficialCurrencyCode(transaction.getUnofficialCurrencyCode());
            transactionToChange.setDatetime(transaction.getDatetime() == null ? null : transaction.getDatetime().toInstant());
            transactionToChange.setDate(transaction.getDate());
            transactionToChange.setPending(transaction.getPending());
            transactionToChange.setPendingTransactionId(transaction.getPendingTransactionId());
            transactionToChange.setMerchantName(transaction.getMerchantName());

            transactionsToUpdate.add(transactionToChange);
        }
    }

    public void handleDeletedTransactions(@NonNull List<RemovedTransaction> deleted, PlaidConnection connection,
                                          List<com.moneyup.backend.Transaction> transactionsToDelete) throws IOException {
        if (deleted.isEmpty()) return;

        for (RemovedTransaction transaction : deleted) {
            String plaidAccountId = transaction.getAccountId();

            Accounts accounts =  accountsRepository.findByPlaidAccountIdAndPlaidConnectionId(plaidAccountId, connection.getId())
                    .orElseThrow(() -> new IOException("Plaid account not found: " + plaidAccountId));
            UUID accountId = accounts.getId();

            String plaidTransactionId = transaction.getTransactionId();

            Optional<com.moneyup.backend.Transaction> transactionToDelete =
                    transactionRepository.findByAccountIdAndPlaidTransactionId(accountId, plaidTransactionId);

            if (transactionToDelete.isEmpty()) {
                continue;
            }

            transactionsToDelete.add(transactionToDelete.get());
        }
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
