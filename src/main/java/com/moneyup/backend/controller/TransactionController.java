package com.moneyup.backend.controller;

import com.moneyup.backend.PlaidConnection;
import com.moneyup.backend.Repository.PlaidConnectionRepository;
import com.moneyup.backend.dto.AllTransactionResponse;
import com.moneyup.backend.dto.PlaidWebhookRequest;
import com.moneyup.backend.service.PlaidWebhookVerificationService;
import com.moneyup.backend.service.TransactionService;
import com.moneyup.backend.service.TransactionSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

@RestController
public class TransactionController {
    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    private final TransactionService transactionService;
    private final PlaidConnectionRepository plaidConnectionRepository;
    private final TransactionSyncService transactionSyncService;
    private final PlaidWebhookVerificationService webhookVerificationService;
    private final JsonMapper jsonMapper;

    public TransactionController(TransactionService transactionService, PlaidConnectionRepository plaidConnectionRepository,
                                 TransactionSyncService transactionSyncService,
                                 PlaidWebhookVerificationService webhookVerificationService, JsonMapper jsonMapper) {
        this.transactionService = transactionService;
        this.plaidConnectionRepository = plaidConnectionRepository;
        this.transactionSyncService = transactionSyncService;
        this.webhookVerificationService = webhookVerificationService;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping("/api/transactions")
    public ResponseEntity<List<AllTransactionResponse>> getTransaction(Authentication authentication) {
        return ResponseEntity.ok()
                .body(transactionService.getTransactions(authentication.getName()));

    }

    @PostMapping("/api/plaid/webhook")
    public ResponseEntity<Void> updateTransactions(
            @RequestHeader(value = "Plaid-Verification", required = false) String token,
            @RequestBody byte[] rawBody) {
        try {
            if (!webhookVerificationService.verify(token, rawBody)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        } catch (IOException exception) {
            log.warn("Could not retrieve the Plaid webhook verification key", exception);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        PlaidWebhookRequest webhook;
        try {
            webhook = jsonMapper.readValue(rawBody, PlaidWebhookRequest.class);
        } catch (JacksonException exception) {
            return ResponseEntity.badRequest().build();
        }
        if (webhook == null || webhook.webhookType() == null || webhook.webhookCode() == null) {
            return ResponseEntity.badRequest().build();
        }

        if (!"TRANSACTIONS".equals(webhook.webhookType())
                || !"SYNC_UPDATES_AVAILABLE".equals(webhook.webhookCode())) {
            return ResponseEntity.noContent().build();
        }
        if (webhook.itemId() == null || webhook.itemId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        PlaidConnection connection = plaidConnectionRepository.findByItemId(webhook.itemId()).orElse(null);
        if (connection != null) {
            try {
                transactionSyncService.syncTransactions(connection);
            } catch (IOException exception) {
                log.warn("Could not sync transactions for a verified Plaid webhook", exception);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            }
        }
        return ResponseEntity.noContent().build();
    }

}
