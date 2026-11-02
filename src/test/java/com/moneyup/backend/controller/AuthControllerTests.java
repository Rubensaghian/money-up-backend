package com.moneyup.backend.controller;

import com.moneyup.backend.Repository.UserRepository;
import com.moneyup.backend.User;
import com.moneyup.backend.config.PasswordConfig;
import com.moneyup.backend.config.SecurityConfig;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AuthControllerTests.Config.class)
@WebAppConfiguration
class AuthControllerTests {
    private static final String LOGIN = "{\"username\":\"alice\",\"password\":\"test-password\"}";
    private static final String SIGNUP = "{\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"test-password\"}";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository repository;
    @Autowired PasswordEncoder passwordEncoder;

    private MockMvc mvc;
    private Map<String, User> users;

    @BeforeEach
    void setup() {
        users = new HashMap<>();
        reset(repository);
        when(repository.findByUsername(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(users.get(invocation.getArgument(0))));
        when(repository.existsByUsername(anyString())).thenAnswer(invocation ->
                users.containsKey(invocation.getArgument(0)));
        when(repository.existsByEmail(anyString())).thenAnswer(invocation ->
                users.values().stream().anyMatch(user -> user.getEmail().equals(invocation.getArgument(0))));
        when(repository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            users.put(user.getUsername(), user);
            return user;
        });

        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void csrfTokenIsAvailableBeforeLogin() throws Exception {
        mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void jsonLoginRotatesSessionAndPersistsAuthentication() throws Exception {
        existingUser();
        Token csrf = csrf(null);
        String anonymousSessionId = csrf.session().getId();

        mvc.perform(jsonPost("/api/auth/login", LOGIN, csrf))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.password").doesNotExist());

        assertNotEquals(anonymousSessionId, csrf.session().getId());
        mvc.perform(get("/api/me").session(csrf.session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void signupHashesPasswordAndLogsUserInImmediately() throws Exception {
        Token csrf = csrf(null);
        String anonymousSessionId = csrf.session().getId();

        mvc.perform(jsonPost("/api/auth/signup", SIGNUP, csrf))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"));

        assertNotEquals(anonymousSessionId, csrf.session().getId());
        assertNotEquals("test-password", users.get("alice").getPasswordHash());
        assertTrue(passwordEncoder.matches("test-password", users.get("alice").getPasswordHash()));
        mvc.perform(get("/api/me").session(csrf.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void wrongPasswordReturnsJsonErrorAndDoesNotAuthenticate() throws Exception {
        existingUser();
        Token csrf = csrf(null);

        mvc.perform(jsonPost("/api/auth/login", LOGIN.replace("test-password", "wrong-password"), csrf))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password."));
        mvc.perform(get("/test/protected").session(csrf.session()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authPostsWithoutCsrfAreRejectedBeforeAccessingUsers() throws Exception {
        for (String path : List.of("/api/auth/login", "/api/auth/signup")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(repository);
    }

    @Test
    void invalidCsrfIsRejected() throws Exception {
        Token csrf = csrf(null);
        mvc.perform(post("/api/auth/login").session(csrf.session())
                        .header(csrf.headerName(), "invalid-token")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }

    @Test
    void loginInvalidatesOldCsrfAndFreshTokenAllowsAuthenticatedPost() throws Exception {
        existingUser();
        Token oldToken = csrf(null);
        mvc.perform(jsonPost("/api/auth/login", LOGIN, oldToken)).andExpect(status().isOk());

        mvc.perform(jsonPost("/test/protected", "{}", oldToken)).andExpect(status().isForbidden());
        Token freshToken = csrf(oldToken.session());
        mvc.perform(jsonPost("/test/protected", "{}", freshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void invalidSignupReturnsValidationErrorWithoutSavingUser() throws Exception {
        mvc.perform(jsonPost("/api/auth/signup", SIGNUP.replace("alice@example.com", "invalid-email"), csrf(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid email format"));
        verify(repository, never()).save(any());
    }

    @Test
    void duplicateUsernameReturnsConflict() throws Exception {
        existingUser();
        mvc.perform(jsonPost("/api/auth/signup", SIGNUP, csrf(null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username is already in use."));
        verify(repository, never()).save(any());
    }

    @Test
    void duplicateEmailReturnsConflict() throws Exception {
        existingUser();
        mvc.perform(jsonPost("/api/auth/signup", SIGNUP.replace("\"alice\"", "\"bob\""), csrf(null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email is already in use."));
    }

    @Test
    void concurrentDuplicateSignupReturnsConflict() throws Exception {
        when(repository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("unique constraint"));
        mvc.perform(jsonPost("/api/auth/signup", SIGNUP, csrf(null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username or email is already in use."));
    }

    @Test
    void malformedLoginJsonReturnsBadRequest() throws Exception {
        mvc.perform(jsonPost("/api/auth/login", "{", csrf(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please provide valid account details."));
    }

    @Test
    void sessionCheckRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Please log in to continue."));
    }

    @Test
    void otherAuthEndpointsAreProtectedByDefault() throws Exception {
        mvc.perform(get("/api/auth/internal"))
                .andExpect(status().isUnauthorized());

        existingUser();
        Token csrf = csrf(null);
        mvc.perform(jsonPost("/api/auth/login", LOGIN, csrf)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/internal").session(csrf.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void wrongHttpMethodDoesNotMakeAnEndpointPublic() throws Exception {
        mvc.perform(get("/api/auth/login")).andExpect(status().isUnauthorized());
        mvc.perform(jsonPost("/api/auth/csrf", "{}", csrf(null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        existingUser();
        Token csrf = csrf(null);
        mvc.perform(jsonPost("/api/auth/login", LOGIN, csrf)).andExpect(status().isOk());
        Token logoutToken = csrf(csrf.session());
        mvc.perform(jsonPost("/api/auth/logout", "{}", logoutToken))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("JSESSIONID", 0));

        assertTrue(csrf.session().isInvalid());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutCsrfDoesNotInvalidateTheSession() throws Exception {
        existingUser();
        Token csrf = csrf(null);
        mvc.perform(jsonPost("/api/auth/login", LOGIN, csrf)).andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").session(csrf.session()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me").session(csrf.session()))
                .andExpect(status().isOk());
    }

    private Token csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var csrf = (CsrfToken) result.getRequest().getAttribute(CsrfToken.class.getName());
        return new Token((MockHttpSession) result.getRequest().getSession(false), csrf.getHeaderName(), csrf.getToken());
    }

    private MockHttpServletRequestBuilder jsonPost(String path, String body, Token csrf) {
        return post(path).session(csrf.session()).header(csrf.headerName(), csrf.token())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void existingUser() {
        User user = new User();
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPasswordHash(passwordEncoder.encode("test-password"));
        users.put(user.getUsername(), user);
    }

    private record Token(MockHttpSession session, String headerName, String token) {}

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, PasswordConfig.class, AuthController.class, AuthExceptionHandler.class, SessionController.class,
            UserService.class, MoneyUpUserDetailsService.class, ProtectedController.class})
    static class Config {
        @Bean UserRepository repository() { return mock(UserRepository.class); }
    }

    @RestController
    static class ProtectedController {
        @GetMapping("/test/protected")
        Map<String, String> currentUser(Principal principal) { return Map.of("username", principal.getName()); }

        @GetMapping("/api/auth/internal")
        Map<String, String> internal(Principal principal) { return Map.of("username", principal.getName()); }

        @PostMapping("/test/protected")
        Map<String, String> protectedWrite(Principal principal) { return Map.of("username", principal.getName()); }
    }
}
