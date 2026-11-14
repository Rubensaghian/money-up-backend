package com.moneyup.backend.service;


import com.moneyup.backend.Accounts;
import com.moneyup.backend.PlaidConnection;
import com.moneyup.backend.Repository.AccountsRepository;
import com.moneyup.backend.Repository.PlaidConnectionRepository;
import com.moneyup.backend.Repository.TransactionRepository;
import com.moneyup.backend.Repository.UserRepository;
import com.moneyup.backend.Transaction;
import com.moneyup.backend.User;
import com.moneyup.backend.dto.AllTransactionResponse;
import com.moneyup.backend.dto.TransactionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final AccountsRepository accountsRepository;
    private final PlaidConnectionRepository plaidConnectionRepository;

    public TransactionService(TransactionRepository transactionRepository,
                              UserRepository userRepository,
                              AccountsRepository accountsRepository,
                              PlaidConnectionRepository plaidConnectionRepository) {
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.accountsRepository = accountsRepository;
        this.plaidConnectionRepository = plaidConnectionRepository;
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in again."));
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
                        transaction.getIsoCurrencyCode() : transaction.getUnofficialCurrencyCode(), transaction.getMerchantName(),
                        transaction.getDate(), transaction.getPending());
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
