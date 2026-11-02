package com.moneyup.backend.controller;

import com.moneyup.backend.service.PlaidService;
import com.moneyup.backend.dto.PlaidConnectionResponse;
import com.moneyup.backend.dto.PublicTokenRequest;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
public class PlaidController {

   private final PlaidService plaidService;

    public PlaidController(PlaidService plaidService) {
       this.plaidService = plaidService;
    }

    @PostMapping("/generate_link_token")
    public Map<String,String> generateLinkToken(Authentication authentication) throws IOException {
        String username = authentication.getName();

        String linkToken = plaidService.createLinkToken(username);

        return Map.of("link_token", linkToken);
    }

    @PostMapping("/swap_public_token")
    public Map<String,String> swapPublicToken(
            @Valid @RequestBody PublicTokenRequest request, Authentication authentication) throws IOException {

        String username = authentication.getName();

        String publicToken = request.publicToken();
        String connectionId = plaidService.swapPublicToken(publicToken, username);
        return Map.of("connection_id", connectionId);
    }

    @GetMapping("/api/accounts")
    public ResponseEntity<List<PlaidConnectionResponse>> getAccounts(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(plaidService.getAccounts(authentication.getName()));
    }

    @PostMapping("/api/accounts/refresh")
    public ResponseEntity<List<PlaidConnectionResponse>> refreshAccounts(Authentication authentication) throws IOException {
        plaidService.refreshAccounts(authentication.getName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(plaidService.getAccounts(authentication.getName()));
    }

}
