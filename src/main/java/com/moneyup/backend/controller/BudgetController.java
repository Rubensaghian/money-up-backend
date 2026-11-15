package com.moneyup.backend.controller;

import com.moneyup.backend.dto.*;
import com.moneyup.backend.service.BudgetService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/budgets")
public class BudgetController {
    private final BudgetService budgets;

    public BudgetController(BudgetService budgets) { this.budgets = budgets; }

    @GetMapping
    public List<BudgetResponse> getBudgets(Authentication authentication) {
        return budgets.getBudgets(authentication.getName());
    }

    @GetMapping("/{id}")
    public BudgetDetailResponse getBudget(@PathVariable UUID id, Authentication authentication) {
        return budgets.getBudget(id, authentication.getName());
    }

    @PostMapping
    public ResponseEntity<BudgetResponse> createBudget(@Valid @RequestBody BudgetRequest request, Authentication authentication) {
        BudgetResponse budget = budgets.createBudget(request, authentication.getName());
        return ResponseEntity.created(URI.create("/api/budgets/" + budget.id())).body(budget);
    }

    @PutMapping("/{id}")
    public BudgetResponse updateBudget(@PathVariable UUID id, @Valid @RequestBody BudgetRequest request, Authentication authentication) {
        return budgets.updateBudget(id, request, authentication.getName());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBudget(@PathVariable UUID id, Authentication authentication) {
        budgets.deleteBudget(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/transactions/{transactionId}/exclusion")
    public BudgetDetailResponse setExcluded(@PathVariable UUID id, @PathVariable UUID transactionId,
                                           @Valid @RequestBody BudgetExclusionRequest request, Authentication authentication) {
        return budgets.setExcluded(id, transactionId, request.excluded(), authentication.getName());
    }
}
