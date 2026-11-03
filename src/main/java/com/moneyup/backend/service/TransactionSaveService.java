package com.moneyup.backend.service;

import com.moneyup.backend.PlaidConnection;
import com.moneyup.backend.Repository.PlaidConnectionRepository;
import com.moneyup.backend.Repository.TransactionRepository;
import com.moneyup.backend.Transaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TransactionSaveService {

    private final TransactionRepository transactionRepository;
    private final PlaidConnectionRepository plaidConnectionRepository;

    public TransactionSaveService(TransactionRepository transactionRepository, PlaidConnectionRepository plaidConnectionRepository) {
        this.transactionRepository = transactionRepository;
        this.plaidConnectionRepository = plaidConnectionRepository;
    }



    @Transactional
    public void saveTransaction(List<Transaction> transactionsToUpdate,
                                List<Transaction> transactionsToDelete,
                                String nextCursor,
                                PlaidConnection connection) {

        transactionRepository.saveAll(transactionsToUpdate);
        transactionRepository.deleteAll(transactionsToDelete);

        connection.setCursor(nextCursor);
        plaidConnectionRepository.save(connection);
    }

}
