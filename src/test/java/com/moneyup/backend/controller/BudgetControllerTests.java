package com.moneyup.backend.controller;

import com.moneyup.backend.BudgetInterval;
import com.moneyup.backend.config.PasswordConfig;
import com.moneyup.backend.config.SecurityConfig;
import com.moneyup.backend.dto.BudgetResponse;
import com.moneyup.backend.dto.BudgetDetailResponse;
import com.moneyup.backend.service.BudgetService;
import com.moneyup.backend.service.MoneyUpUserDetailsService;
import com.moneyup.backend.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = BudgetControllerTests.Config.class)
@WebAppConfiguration
class BudgetControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired BudgetService budgets;
    private MockMvc mvc;
    private final UUID budgetId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        reset(budgets);
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void listingAndReadingRequireAuthentication() throws Exception {
        mvc.perform(get("/api/budgets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/budgets/" + budgetId)).andExpect(status().isUnauthorized());
        verifyNoInteractions(budgets);
    }

    @Test
    void everyMutationRequiresCsrf() throws Exception {
        MockHttpSession session = session();
        mvc.perform(post("/api/budgets").session(session).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/budgets/" + budgetId).session(session).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/budgets/" + budgetId).session(session)).andExpect(status().isForbidden());
        mvc.perform(put(exclusionUrl()).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"excluded\":true}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(budgets);
    }

    @Test
    void listUsesTheSessionAndReturnsOnlyDisplayData() throws Exception {
        when(budgets.getBudgets("alice")).thenReturn(List.of(summary()));
        mvc.perform(get("/api/budgets").session(session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Monthly"))
                .andExpect(jsonPath("$[0].spent").value(25)).andExpect(jsonPath("$[0].remaining").value(975))
                .andExpect(jsonPath("$[0].userId").doesNotExist());
        verify(budgets).getBudgets("alice");
    }

    @Test
    void creationReturnsTheCreatedBudgetAndLocation() throws Exception {
        when(budgets.createBudget(any(), eq("alice"))).thenReturn(summary());
        mvc.perform(withCsrf(post("/api/budgets")).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/budgets/" + budgetId))
                .andExpect(jsonPath("$.id").value(budgetId.toString()));
        verify(budgets).createBudget(argThat(request -> request.accountIds().contains(accountId)
                && request.amount().compareTo(new BigDecimal("1000")) == 0), eq("alice"));
    }

    @Test
    void invalidLimitsIntervalsAndSelectionsNeverReachTheService() throws Exception {
        for (String invalid : List.of(
                validBody().replace("1000", "-1"), validBody().replace("1000", "0"),
                validBody().replace("1000", "0.12345"), validBody().replace("MONTHLY", "SOMETIMES"),
                validBody().replace("Monthly", " "), validBody().replace("USD", "US"),
                validBody().replace("\"" + accountId + "\"", ""),
                validBody().replace("\"" + accountId + "\"", "null"))) {
            mvc.perform(withCsrf(post("/api/budgets")).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        }
        verifyNoInteractions(budgets);
    }

    @Test
    void updateUsesTheBudgetIdAndTheSessionOwner() throws Exception {
        when(budgets.updateBudget(eq(budgetId), any(), eq("alice"))).thenReturn(summary());
        mvc.perform(withCsrf(put("/api/budgets/" + budgetId)).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isOk());
        verify(budgets).updateBudget(eq(budgetId), any(), eq("alice"));
    }

    @Test
    void hidingAndRestoringUseAnExplicitBoolean() throws Exception {
        when(budgets.setExcluded(eq(budgetId), eq(transactionId), anyBoolean(), eq("alice")))
                .thenReturn(new BudgetDetailResponse(summary(), List.of()));
        for (boolean excluded : List.of(true, false)) {
            mvc.perform(withCsrf(put(exclusionUrl())).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"excluded\":" + excluded + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.budget.id").value(budgetId.toString()));
            verify(budgets).setExcluded(budgetId, transactionId, excluded, "alice");
        }
    }

    @Test
    void missingExclusionFlagIsRejected() throws Exception {
        mvc.perform(withCsrf(put(exclusionUrl())).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(budgets);
    }

    @Test
    void anotherUsersBudgetReturnsNotFound() throws Exception {
        when(budgets.getBudget(budgetId, "alice")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found."));
        mvc.perform(get("/api/budgets/" + budgetId).session(session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Not found."));
    }

    @Test
    void invalidAccountSelectionReturnsTheServiceValidationError() throws Exception {
        when(budgets.createBudget(any(), eq("alice"))).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select only accounts that belong to you."));
        mvc.perform(withCsrf(post("/api/budgets")).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Select only accounts that belong to you."));
    }

    @Test
    void deleteReturnsNoContentAndUsesTheSessionOwner() throws Exception {
        mvc.perform(withCsrf(delete("/api/budgets/" + budgetId))).andExpect(status().isNoContent());
        verify(budgets).deleteBudget(budgetId, "alice");
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpSession session = session();
        CsrfToken token = (CsrfToken) mvc.perform(get("/api/auth/csrf").session(session))
                .andReturn().getRequest().getAttribute(CsrfToken.class.getName());
        return request.session(session).header(token.getHeaderName(), token.getToken());
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private String validBody() {
        return """
                {"name":"Monthly","interval":"MONTHLY","amount":1000,"currencyCode":"USD","accountIds":["%s"]}
                """.formatted(accountId);
    }

    private String exclusionUrl() { return "/api/budgets/" + budgetId + "/transactions/" + transactionId + "/exclusion"; }

    private BudgetResponse summary() {
        return new BudgetResponse(budgetId, "Monthly", BudgetInterval.MONTHLY, new BigDecimal("1000"), "USD",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "UTC", new BigDecimal("25"),
                new BigDecimal("975"), 0, List.of());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, PasswordConfig.class, BudgetController.class, BudgetExceptionHandler.class,
            AuthController.class, AuthExceptionHandler.class})
    static class Config {
        @Bean BudgetService budgets() { return mock(BudgetService.class); }
        @Bean UserService users() { return mock(UserService.class); }
        @Bean MoneyUpUserDetailsService userDetailsService() { return mock(MoneyUpUserDetailsService.class); }
    }
}
