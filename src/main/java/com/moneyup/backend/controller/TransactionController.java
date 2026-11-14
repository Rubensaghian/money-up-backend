package com.moneyup.backend.controller;

import com.moneyup.backend.dto.AllTransactionResponse;
import com.moneyup.backend.service.TransactionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping("/api/transactions")
    public ResponseEntity<List<AllTransactionResponse>> addTransaction(Authentication authentication) {
        return ResponseEntity.ok()
                .body(transactionService.getTransactions(authentication.getName()));

    }

}
