package com.moneyup.backend.service;

import com.moneyup.backend.*;
import com.moneyup.backend.Repository.*;
import com.moneyup.backend.dto.BudgetRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BudgetServiceTests {
    private BudgetRepository budgets;
    private UserRepository users;
    private AccountsRepository accounts;
    private PlaidConnectionRepository connections;
    private TransactionRepository transactions;
    private BudgetService service;
    private User user;
    private PlaidConnection connection;
    private Accounts checking;
    private Accounts card;
    private Budget budget;

    @BeforeEach
    void setup() {
        budgets = mock(BudgetRepository.class);
        users = mock(UserRepository.class);
        accounts = mock(AccountsRepository.class);
        connections = mock(PlaidConnectionRepository.class);
        transactions = mock(TransactionRepository.class);
        service = new BudgetService(budgets, users, accounts, connections, transactions,
                Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC));
        user = new User();
        user.setId(UUID.randomUUID());
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        connection = new PlaidConnection();
        connection.setId(UUID.randomUUID());
        connection.setUserId(user.getId());
        connection.setInstitutionName("Test bank");
        when(connections.findAllByUserId(user.getId())).thenReturn(List.of(connection));
        checking = account("Checking");
        card = account("Card");
        when(accounts.findAllById(any())).thenAnswer(invocation -> {
            Set<UUID> ids = invocation.getArgument(0);
            return List.of(checking, card).stream().filter(account -> ids.contains(account.getId())).toList();
        });
        budget = new Budget();
        budget.setId(UUID.randomUUID());
        budget.setUserId(user.getId());
        budget.setName("Monthly spending");
        budget.setInterval(BudgetInterval.MONTHLY);
        budget.setAmount(new BigDecimal("1000"));
        budget.setCurrencyCode("USD");
        budget.getAccountIds().addAll(Set.of(checking.getId(), card.getId()));
        when(budgets.findByIdAndUserId(budget.getId(), user.getId())).thenReturn(Optional.of(budget));
        when(budgets.findOwnedForUpdate(budget.getId(), user.getId())).thenReturn(Optional.of(budget));
        when(transactions.findPostedForBudget(anyCollection(), any(), any())).thenReturn(List.of());
        when(budgets.save(any())).thenAnswer(invocation -> {
            Budget saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
    }

    @Test
    void createsABudgetOwnedByTheAuthenticatedUserWithMultipleAccounts() {
        var result = service.createBudget(request(), "alice");
        assertEquals("Monthly spending", result.name());
        assertEquals(2, result.accounts().size());
        verify(budgets).save(argThat(saved -> saved.getUserId().equals(user.getId())
                && saved.getAccountIds().equals(Set.of(checking.getId(), card.getId()))));
    }

    @Test
    void countsPositivePostedAmountsExactlyAndSkipsIncomingHiddenAndOtherCurrencies() {
        budget.setAmount(new BigDecimal("10.00"));
        Transaction first = transaction(checking, "0.10");
        Transaction second = transaction(card, "0.20");
        Transaction income = transaction(checking, "-500");
        Transaction hidden = transaction(card, "80");
        Transaction foreign = transaction(checking, "20");
        foreign.setIsoCurrencyCode("EUR");
        Transaction zero = transaction(checking, "0");
        budget.getExcludedTransactionIds().add(hidden.getId());
        when(transactions.findPostedForBudget(anyCollection(), any(), any()))
                .thenReturn(List.of(first, second, income, hidden, foreign, zero));

        var result = service.getBudget(budget.getId(), "alice");
        assertEquals(new BigDecimal("0.30"), result.budget().spent());
        assertEquals(new BigDecimal("9.70"), result.budget().remaining());
        assertEquals(1, result.budget().excludedTransactionCount());
        assertEquals(List.of("COUNTED", "COUNTED", "MONEY_IN", "HIDDEN", "OTHER_CURRENCY", "ZERO_AMOUNT"),
                result.transactions().stream().map(row -> row.treatment()).toList());
        verify(transactions).findPostedForBudget(Set.of(checking.getId(), card.getId()),
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1));
    }

    @Test
    void overspendingProducesANegativeRemainingAmount() {
        when(transactions.findPostedForBudget(anyCollection(), any(), any()))
                .thenReturn(List.of(transaction(checking, "1200")));
        assertEquals(new BigDecimal("-200"), service.getBudget(budget.getId(), "alice").budget().remaining());
    }

    @Test
    void hidingAndRestoringAreIdempotentAndOnlyAffectThisBudget() {
        Transaction transaction = transaction(checking, "25");
        when(transactions.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(transactions.findPostedForBudget(anyCollection(), any(), any())).thenReturn(List.of(transaction));
        Budget anotherBudget = new Budget();

        var hidden = service.setExcluded(budget.getId(), transaction.getId(), true, "alice");
        service.setExcluded(budget.getId(), transaction.getId(), true, "alice");
        assertEquals(BigDecimal.ZERO, hidden.budget().spent());
        assertEquals(Set.of(transaction.getId()), budget.getExcludedTransactionIds());
        assertTrue(anotherBudget.getExcludedTransactionIds().isEmpty());
        var restored = service.setExcluded(budget.getId(), transaction.getId(), false, "alice");
        service.setExcluded(budget.getId(), transaction.getId(), false, "alice");
        assertEquals(new BigDecimal("25"), restored.budget().spent());
        assertTrue(budget.getExcludedTransactionIds().isEmpty());
        verify(transactions, never()).save(any());
        verify(transactions, never()).delete(any());
    }

    @Test
    void cannotHideATransactionFromAnUnselectedAccount() {
        Transaction other = transaction(account("Other account"), "30");
        when(transactions.findById(other.getId())).thenReturn(Optional.of(other));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.setExcluded(budget.getId(), other.getId(), true, "alice"));
        assertTrue(budget.getExcludedTransactionIds().isEmpty());
    }

    @Test
    void cannotHideAPendingTransaction() {
        Transaction pending = transaction(checking, "30");
        pending.setPending(true);
        when(transactions.findById(pending.getId())).thenReturn(Optional.of(pending));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.setExcluded(budget.getId(), pending.getId(), true, "alice"));
    }

    @Test
    void cannotReadOrModifyAnotherUsersBudget() {
        UUID otherId = UUID.randomUUID();
        assertStatus(HttpStatus.NOT_FOUND, () -> service.getBudget(otherId, "alice"));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.updateBudget(otherId, request(), "alice"));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.deleteBudget(otherId, "alice"));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.setExcluded(otherId, UUID.randomUUID(), true, "alice"));
        verifyNoInteractions(transactions);
    }

    @Test
    void rejectsAccountsFromAnotherUserBeforeSaving() {
        card.setPlaidConnectionId(UUID.randomUUID());
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createBudget(request(), "alice"));
        verify(budgets, never()).save(any());
        verifyNoInteractions(transactions);
    }

    @Test
    void persistedAccountLinksAreAlsoCheckedBeforeReadingTransactions() {
        card.setPlaidConnectionId(UUID.randomUUID());
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.getBudget(budget.getId(), "alice"));
        verifyNoInteractions(transactions);
    }

    @Test
    void rejectsMissingAndMixedCurrencyAccounts() {
        BudgetRequest missing = new BudgetRequest("Missing", BudgetInterval.MONTHLY, BigDecimal.TEN,
                "USD", Set.of(UUID.randomUUID()));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createBudget(missing, "alice"));
        card.setIsoCurrencyCode("EUR");
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createBudget(request(), "alice"));
        verify(budgets, never()).save(any());
    }

    @Test
    void cannotAddInactiveAccountsButCanKeepAnExistingInactiveAccount() {
        card.setActive(false);
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createBudget(request(), "alice"));
        assertEquals(2, service.updateBudget(budget.getId(), request(), "alice").accounts().size());
    }

    @Test
    void editingReplacesTheAccountSelectionAndKeepsExclusionChoices() {
        UUID excluded = UUID.randomUUID();
        budget.getExcludedTransactionIds().add(excluded);
        var request = new BudgetRequest(" Checking only ", BudgetInterval.WEEKLY, new BigDecimal("500"),
                "usd", Set.of(checking.getId()));
        var result = service.updateBudget(budget.getId(), request, "alice");
        assertEquals(Set.of(checking.getId()), budget.getAccountIds());
        assertEquals("Checking only", result.name());
        assertEquals("USD", result.currencyCode());
        assertEquals(Set.of(excluded), budget.getExcludedTransactionIds());
        verify(transactions).findPostedForBudget(Set.of(checking.getId()), LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 16));
    }

    @Test
    void calculatesCalendarBoundariesWithAnExclusiveEnd() {
        Map<BudgetInterval, LocalDate[]> expected = Map.of(
                BudgetInterval.DAILY, new LocalDate[]{LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)},
                BudgetInterval.WEEKLY, new LocalDate[]{LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 16)},
                BudgetInterval.MONTHLY, new LocalDate[]{LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)},
                BudgetInterval.YEARLY, new LocalDate[]{LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)});
        expected.forEach((interval, dates) -> {
            clearInvocations(transactions);
            budget.setInterval(interval);
            var result = service.getBudget(budget.getId(), "alice").budget();
            assertEquals(dates[0], result.periodStart());
            assertEquals(dates[1].minusDays(1), result.periodEnd());
            verify(transactions).findPostedForBudget(budget.getAccountIds(), dates[0], dates[1]);
        });
    }

    @Test
    void calendarPeriodsUseTheConfiguredTimeZone() {
        service = new BudgetService(budgets, users, accounts, connections, transactions,
                Clock.fixed(Instant.parse("2026-12-01T00:30:00Z"), ZoneId.of("America/Los_Angeles")));
        var result = service.getBudget(budget.getId(), "alice").budget();
        assertEquals(LocalDate.of(2026, 11, 1), result.periodStart());
        assertEquals("America/Los_Angeles", result.timeZone());
    }

    @Test
    void anAccountRemovedFromTheBudgetDoesNotLeakIntoItsSummary() {
        budget.getAccountIds().clear();
        var result = service.getBudget(budget.getId(), "alice");
        assertEquals(BigDecimal.ZERO, result.budget().spent());
        assertTrue(result.transactions().isEmpty());
        verifyNoInteractions(transactions);
    }

    @Test
    void deletionUsesTheOwnerScopedLookupAndDoesNotDeleteBankData() {
        service.deleteBudget(budget.getId(), "alice");
        verify(budgets).findOwnedForUpdate(budget.getId(), user.getId());
        verify(budgets).delete(budget);
        verifyNoInteractions(transactions, accounts);
    }

    @Test
    void listAndUnknownUserLookupsAreScopedToTheSession() {
        when(budgets.findAllByUserIdOrderByNameAsc(user.getId())).thenReturn(List.of(budget));
        assertEquals(1, service.getBudgets("alice").size());
        verify(budgets).findAllByUserIdOrderByNameAsc(user.getId());
        assertStatus(HttpStatus.UNAUTHORIZED, () -> service.getBudgets("unknown"));
    }

    private Accounts account(String name) {
        Accounts account = new Accounts();
        account.setId(UUID.randomUUID());
        account.setName(name);
        account.setPlaidConnectionId(connection.getId());
        account.setIsoCurrencyCode("USD");
        return account;
    }

    private Transaction transaction(Accounts account, String amount) {
        Transaction transaction = new Transaction();
        ReflectionTestUtils.setField(transaction, "id", UUID.randomUUID());
        transaction.setAccountId(account.getId());
        transaction.setAmount(new BigDecimal(amount));
        transaction.setIsoCurrencyCode("USD");
        transaction.setPending(false);
        transaction.setDate(LocalDate.of(2026, 11, 12));
        return transaction;
    }

    private BudgetRequest request() {
        return new BudgetRequest("Monthly spending", BudgetInterval.MONTHLY, new BigDecimal("1000"),
                "USD", Set.of(checking.getId(), card.getId()));
    }

    private void assertStatus(HttpStatus status, Runnable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action::run).getStatusCode());
    }
}
