package com.moneyup.backend.service;

import com.moneyup.backend.*;
import com.moneyup.backend.Repository.*;
import com.moneyup.backend.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BudgetService {
    private final BudgetRepository budgets;
    private final UserRepository users;
    private final AccountsRepository accounts;
    private final PlaidConnectionRepository connections;
    private final TransactionRepository transactions;
    private final Clock clock;

    public BudgetService(BudgetRepository budgets, UserRepository users, AccountsRepository accounts,
                         PlaidConnectionRepository connections, TransactionRepository transactions, Clock clock) {
        this.budgets = budgets;
        this.users = users;
        this.accounts = accounts;
        this.connections = connections;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BudgetResponse> getBudgets(String username) {
        User user = currentUser(username);
        Map<UUID, PlaidConnection> ownedConnections = ownedConnections(user);
        return budgets.findAllByUserIdOrderByNameAsc(user.getId()).stream()
                .map(budget -> detail(budget, ownedConnections).budget()).toList();
    }

    @Transactional(readOnly = true)
    public BudgetDetailResponse getBudget(UUID id, String username) {
        User user = currentUser(username);
        Budget budget = budgets.findByIdAndUserId(id, user.getId()).orElseThrow(this::notFound);
        return detail(budget, ownedConnections(user));
    }

    @Transactional
    public BudgetResponse createBudget(BudgetRequest request, String username) {
        User user = currentUser(username);
        Map<UUID, PlaidConnection> ownedConnections = ownedConnections(user);
        Budget budget = new Budget();
        budget.setUserId(user.getId());
        applyRequest(budget, request, ownedConnections);
        return detail(budgets.save(budget), ownedConnections).budget();
    }

    @Transactional
    public BudgetResponse updateBudget(UUID id, BudgetRequest request, String username) {
        User user = currentUser(username);
        Budget budget = budgets.findOwnedForUpdate(id, user.getId()).orElseThrow(this::notFound);
        Map<UUID, PlaidConnection> ownedConnections = ownedConnections(user);
        applyRequest(budget, request, ownedConnections);
        // Hibernate persists changes to this managed entity when the transaction commits.
        return detail(budget, ownedConnections).budget();
    }

    @Transactional
    public void deleteBudget(UUID id, String username) {
        User user = currentUser(username);
        Budget budget = budgets.findOwnedForUpdate(id, user.getId()).orElseThrow(this::notFound);
        budgets.delete(budget);
    }

    @Transactional
    public BudgetDetailResponse setExcluded(UUID budgetId, UUID transactionId, boolean excluded, String username) {
        User user = currentUser(username);
        // Serialize edits so concurrent hide/restore requests preserve each other's changes.
        Budget budget = budgets.findOwnedForUpdate(budgetId, user.getId()).orElseThrow(this::notFound);
        Map<UUID, PlaidConnection> ownedConnections = ownedConnections(user);
        ownedAccounts(budget.getAccountIds(), ownedConnections);
        Transaction transaction = transactions.findById(transactionId).orElseThrow(this::notFound);
        if (!budget.getAccountIds().contains(transaction.getAccountId()) || Boolean.TRUE.equals(transaction.getPending())) {
            throw notFound();
        }
        if (excluded) {
            budget.getExcludedTransactionIds().add(transactionId);
        } else {
            budget.getExcludedTransactionIds().remove(transactionId);
        }
        return detail(budget, ownedConnections);
    }

    private void applyRequest(Budget budget, BudgetRequest request, Map<UUID, PlaidConnection> ownedConnections) {
        String currency = request.currencyCode().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a supported currency, such as USD.");
        }
        List<Accounts> selected = ownedAccounts(request.accountIds(), ownedConnections);
        for (Accounts account : selected) {
            if (!account.isActive() && !budget.getAccountIds().contains(account.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select active accounts for this budget.");
            }
            if (!currency.equals(currency(account))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "All selected accounts must use the budget's currency.");
            }
        }
        budget.setName(request.name().trim());
        budget.setInterval(request.interval());
        budget.setAmount(request.amount());
        budget.setCurrencyCode(currency);
        budget.getAccountIds().clear();
        budget.getAccountIds().addAll(request.accountIds());
    }

    private BudgetDetailResponse detail(Budget budget, Map<UUID, PlaidConnection> ownedConnections) {
        List<Accounts> selected = ownedAccounts(budget.getAccountIds(), ownedConnections);
        Map<UUID, Accounts> byId = selected.stream().collect(Collectors.toMap(Accounts::getId, Function.identity()));
        Period period = currentPeriod(budget.getInterval());
        List<Transaction> saved = selected.isEmpty() ? List.of()
                : transactions.findPostedForBudget(byId.keySet(), period.start(), period.endExclusive());
        List<BudgetTransactionResponse> rows = new ArrayList<>();
        BigDecimal spent = BigDecimal.ZERO;
        int hidden = 0;
        for (Transaction transaction : saved) {
            String transactionCurrency = currency(transaction);
            boolean excluded = budget.getExcludedTransactionIds().contains(transaction.getId());
            String treatment;
            if (excluded) {
                treatment = "HIDDEN";
                hidden++;
            } else if (!budget.getCurrencyCode().equals(transactionCurrency)) {
                treatment = "OTHER_CURRENCY";
            } else if (transaction.getAmount().signum() < 0) {
                treatment = "MONEY_IN";
            } else if (transaction.getAmount().signum() == 0) {
                treatment = "ZERO_AMOUNT";
            } else {
                treatment = "COUNTED";
                spent = spent.add(transaction.getAmount());
            }
            rows.add(new BudgetTransactionResponse(transaction.getId(), transaction.getAccountId(),
                    byId.get(transaction.getAccountId()).getName(), transaction.getMerchantName(), transaction.getDate(),
                    transaction.getAmount(), transactionCurrency, excluded, treatment));
        }
        List<BudgetAccountResponse> accountResponses = selected.stream()
                .sorted(Comparator.comparing(Accounts::getName).thenComparing(Accounts::getId))
                .map(account -> new BudgetAccountResponse(account.getId(), account.getName(),
                        ownedConnections.get(account.getPlaidConnectionId()).getInstitutionName(),
                        account.getMask(), currency(account), account.isActive())).toList();
        BudgetResponse summary = new BudgetResponse(budget.getId(), budget.getName(), budget.getInterval(),
                budget.getAmount(), budget.getCurrencyCode(), period.start(), period.endExclusive().minusDays(1),
                clock.getZone().getId(), spent, budget.getAmount().subtract(spent), hidden, accountResponses);
        return new BudgetDetailResponse(summary, rows);
    }

    private List<Accounts> ownedAccounts(Set<UUID> ids, Map<UUID, PlaidConnection> ownedConnections) {
        List<Accounts> selected = accounts.findAllById(ids);
        if (selected.size() != ids.size() || selected.stream()
                .anyMatch(account -> !ownedConnections.containsKey(account.getPlaidConnectionId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select only accounts that belong to you.");
        }
        return selected;
    }

    private Map<UUID, PlaidConnection> ownedConnections(User user) {
        return connections.findAllByUserId(user.getId()).stream()
                .collect(Collectors.toMap(PlaidConnection::getId, Function.identity()));
    }

    private User currentUser(String username) {
        return users.findByUsername(username).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in again."));
    }

    private Period currentPeriod(BudgetInterval interval) {
        LocalDate today = LocalDate.now(clock);
        return switch (interval) {
            case DAILY -> new Period(today, today.plusDays(1));
            case WEEKLY -> {
                LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new Period(start, start.plusWeeks(1));
            }
            case MONTHLY -> {
                LocalDate start = today.withDayOfMonth(1);
                yield new Period(start, start.plusMonths(1));
            }
            case YEARLY -> {
                LocalDate start = today.withDayOfYear(1);
                yield new Period(start, start.plusYears(1));
            }
        };
    }

    private String currency(Accounts account) {
        return account.getIsoCurrencyCode() != null ? account.getIsoCurrencyCode() : account.getUnofficialCurrencyCode();
    }

    private String currency(Transaction transaction) {
        return transaction.getIsoCurrencyCode() != null ? transaction.getIsoCurrencyCode() : transaction.getUnofficialCurrencyCode();
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "The budget or transaction was not found.");
    }

    private record Period(LocalDate start, LocalDate endExclusive) {}
}
