package com.moneyup.backend.controller;

import com.moneyup.backend.service.PlaidService;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
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

    @PostMapping("swap_public_token")
    public Map<String,String> swapPublicToken(
            @RequestBody Map<String,String> request, Authentication authentication) throws IOException {

        String username = authentication.getName();

        String publicToken = request.get("public_token");
        String connectionId = plaidService.swapPublicToken(publicToken, username);
        return Map.of("connection_id", connectionId);
    }

}
