package com.moneyup.backend.controller;

import com.moneyup.backend.config.PasswordConfig;
import com.moneyup.backend.config.SecurityConfig;
import com.moneyup.backend.dto.AccountResponse;
import com.moneyup.backend.dto.PlaidConnectionResponse;
import com.moneyup.backend.service.MoneyUpUserDetailsService;
import com.moneyup.backend.service.PlaidService;
import com.moneyup.backend.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PlaidControllerTests.Config.class)
@WebAppConfiguration
class PlaidControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired PlaidService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(service);
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void accountListRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void accountListReturnsSavedDisplayDataWithoutCredentials() throws Exception {
        AccountResponse account = new AccountResponse(UUID.randomUUID(), "Checking", null, "1234", "depository", "checking",
                new BigDecimal("123.45"), null, "USD");
        PlaidConnectionResponse connection = new PlaidConnectionResponse(UUID.randomUUID(), "Demo bank",
                Instant.parse("2026-01-01T00:00:00Z"), List.of(account));
        when(service.getAccounts("alice")).thenReturn(List.of(connection));

        mvc.perform(get("/api/accounts").session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].institutionName").value("Demo bank"))
                .andExpect(jsonPath("$[0].accounts[0].name").value("Checking"))
                .andExpect(jsonPath("$[0].accounts[0].currentBalance").value(123.45))
                .andExpect(jsonPath("$[0].accessToken").doesNotExist())
                .andExpect(jsonPath("$[0].accessTokenEncrypted").doesNotExist())
                .andExpect(jsonPath("$[0].userId").doesNotExist())
                .andExpect(jsonPath("$[0].accounts[0].plaidAccountId").doesNotExist());
        verify(service).getAccounts("alice");
        verify(service, never()).refreshAccounts(anyString());
    }

    @Test
    void noConnectionsIsAnEmptyJsonList() throws Exception {
        when(service.getAccounts("alice")).thenReturn(List.of());
        mvc.perform(get("/api/accounts").session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void refreshRequiresCsrfBeforeCallingPlaid() throws Exception {
        mvc.perform(post("/api/accounts/refresh").session(authenticatedSession()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void refreshUsesTheSignedInUsernameAndReturnsTheSavedList() throws Exception {
        MockHttpSession session = authenticatedSession();
        CsrfToken csrf = csrf(session);
        when(service.getAccounts("alice")).thenReturn(List.of());

        mvc.perform(post("/api/accounts/refresh").session(session)
                        .header(csrf.getHeaderName(), csrf.getToken()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        verify(service).refreshAccounts("alice");
        verify(service).getAccounts("alice");
    }

    @Test
    void providerFailureReturnsAUsefulJsonError() throws Exception {
        MockHttpSession session = authenticatedSession();
        CsrfToken csrf = csrf(session);
        doThrow(new IOException("Provider failure")).when(service).refreshAccounts("alice");

        mvc.perform(post("/api/accounts/refresh").session(session)
                        .header(csrf.getHeaderName(), csrf.getToken()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Could not retrieve bank data from Plaid. Please try again."));
    }

    @Test
    void exchangeRejectsAMissingPublicToken() throws Exception {
        MockHttpSession session = authenticatedSession();
        CsrfToken csrf = csrf(session);
        mvc.perform(post("/swap_public_token").session(session)
                        .header(csrf.getHeaderName(), csrf.getToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A public token is required."));
        verifyNoInteractions(service);
    }

    @Test
    void exchangeAcceptsTheLinkPublicTokenAndReturnsOnlyAnInternalId() throws Exception {
        MockHttpSession session = authenticatedSession();
        CsrfToken csrf = csrf(session);
        String id = UUID.randomUUID().toString();
        when(service.swapPublicToken("public-test", "alice")).thenReturn(id);

        mvc.perform(post("/swap_public_token").session(session)
                        .header(csrf.getHeaderName(), csrf.getToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"public_token\":\"public-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connection_id").value(id))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    private CsrfToken csrf(MockHttpSession session) throws Exception {
        return (CsrfToken) mvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk()).andReturn().getRequest().getAttribute(CsrfToken.class.getName());
    }

    private static MockHttpSession authenticatedSession() {
        MockHttpSession session = new MockHttpSession();
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        return session;
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, PasswordConfig.class, PlaidController.class, PlaidExceptionHandler.class,
            AuthController.class, AuthExceptionHandler.class})
    static class Config {
        @Bean PlaidService plaidService() { return mock(PlaidService.class); }
        @Bean UserService userService() { return mock(UserService.class); }
        @Bean MoneyUpUserDetailsService userDetailsService() { return mock(MoneyUpUserDetailsService.class); }
    }
}
