package com.moneyup.backend.controller;

import com.moneyup.backend.PlaidConnection;
import com.moneyup.backend.Repository.PlaidConnectionRepository;
import com.moneyup.backend.config.PasswordConfig;
import com.moneyup.backend.config.SecurityConfig;
import com.moneyup.backend.service.MoneyUpUserDetailsService;
import com.moneyup.backend.service.PlaidWebhookVerificationService;
import com.moneyup.backend.service.TransactionService;
import com.moneyup.backend.service.TransactionSyncService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = TransactionControllerTests.Config.class)
@WebAppConfiguration
class TransactionControllerTests {
    private static final String EVENT = """
            {
              "webhook_type": "TRANSACTIONS",
              "webhook_code": "SYNC_UPDATES_AVAILABLE",
              "item_id": "item-123",
              "environment": "sandbox",
              "initial_update_complete": true
            }
            """;
    @Autowired WebApplicationContext context;
    @Autowired PlaidWebhookVerificationService verification;
    @Autowired PlaidConnectionRepository connections;
    @Autowired TransactionSyncService sync;
    @Autowired TransactionService transactions;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(verification, connections, sync, transactions);
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void verifiedWebhookWorksWithoutLoginOrCsrfAndSyncsOnlyItsItem() throws Exception {
        acceptSignature();
        PlaidConnection connection = new PlaidConnection();
        when(connections.findByItemId("item-123")).thenReturn(Optional.of(connection));

        mvc.perform(post("/api/plaid/webhook").header("Plaid-Verification", "signed-token")
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isNoContent());

        var order = inOrder(verification, connections, sync);
        order.verify(verification).verify(eq("signed-token"), aryEq(EVENT.getBytes(StandardCharsets.UTF_8)));
        order.verify(connections).findByItemId("item-123");
        order.verify(sync).syncTransactions(connection);
        verifyNoMoreInteractions(connections, sync);
    }

    @Test
    void missingHeaderIsRejectedBeforeDatabaseAccess() throws Exception {
        mvc.perform(post("/api/plaid/webhook").contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isUnauthorized());
        verify(verification).verify(isNull(), any(byte[].class));
        verifyNoInteractions(connections, sync);
    }

    @Test
    void verificationRunsBeforeEvenParsingTheJson() throws Exception {
        mvc.perform(post("/api/plaid/webhook").header("Plaid-Verification", "invalid-token")
                        .contentType(MediaType.APPLICATION_JSON).content("not-json"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(connections, sync);
    }

    @Test
    void invalidJsonWithAValidSignatureReturnsBadRequest() throws Exception {
        acceptSignature();
        mvc.perform(post("/api/plaid/webhook").header("Plaid-Verification", "signed-token")
                        .contentType(MediaType.APPLICATION_JSON).content("not-json"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(connections, sync);
    }

    @Test
    void unrelatedEventsAreAcknowledgedWithoutSyncing() throws Exception {
        acceptSignature();
        mvc.perform(post("/api/plaid/webhook").header("Plaid-Verification", "signed-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"webhook_type\":\"ITEM\",\"webhook_code\":\"ERROR\"}"))
                .andExpect(status().isNoContent());
        verifyNoInteractions(connections, sync);
    }

    @Test
    void unknownItemIsAcknowledgedWithoutSyncing() throws Exception {
        acceptSignature();
        when(connections.findByItemId("item-123")).thenReturn(Optional.empty());
        mvc.perform(post("/api/plaid/webhook").header("Plaid-Verification", "signed-token")
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isNoContent());
        verifyNoInteractions(sync);
    }

    @Test
    void missingItemForTransactionUpdatesIsBadRequest() throws Exception {
        acceptSignature();
        mvc.perform(post("/api/plaid/webhook").header("Plaid-Verification", "signed-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"webhook_type\":\"TRANSACTIONS\",\"webhook_code\":\"SYNC_UPDATES_AVAILABLE\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(connections, sync);
    }

    @Test
    void keyRetrievalFailureReturnsServiceUnavailableWithoutSyncing() throws Exception {
        when(verification.verify(anyString(), any(byte[].class))).thenThrow(new IOException("Plaid unavailable"));
        mvc.perform(post("/api/plaid/webhook").header("Plaid-Verification", "signed-token")
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(connections, sync);
    }

    @Test
    void syncFailureReturnsAnErrorInsteadOfAcknowledgingSuccess() throws Exception {
        acceptSignature();
        PlaidConnection connection = new PlaidConnection();
        when(connections.findByItemId("item-123")).thenReturn(Optional.of(connection));
        doThrow(new IOException("Plaid unavailable")).when(sync).syncTransactions(connection);
        mvc.perform(post("/api/plaid/webhook").header("Plaid-Verification", "signed-token")
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isBadGateway());
    }

    @Test
    void transactionListStillRequiresLogin() throws Exception {
        mvc.perform(get("/api/transactions")).andExpect(status().isUnauthorized());
        verifyNoInteractions(transactions);
    }

    @Test
    void csrfExemptionDoesNotApplyToOtherMethodsOrPaths() throws Exception {
        mvc.perform(put("/api/plaid/webhook")).andExpect(status().isForbidden());
        mvc.perform(post("/api/transactions")).andExpect(status().isForbidden());
        verifyNoInteractions(verification, connections, sync, transactions);
    }

    private void acceptSignature() throws IOException {
        when(verification.verify(eq("signed-token"), any(byte[].class))).thenReturn(true);
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, PasswordConfig.class, TransactionController.class})
    static class Config {
        @Bean TransactionService transactions() { return mock(TransactionService.class); }
        @Bean PlaidConnectionRepository connections() { return mock(PlaidConnectionRepository.class); }
        @Bean TransactionSyncService sync() { return mock(TransactionSyncService.class); }
        @Bean PlaidWebhookVerificationService verification() { return mock(PlaidWebhookVerificationService.class); }
        @Bean MoneyUpUserDetailsService userDetailsService() { return mock(MoneyUpUserDetailsService.class); }
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
    }
}
