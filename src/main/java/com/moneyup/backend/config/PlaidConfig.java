package com.moneyup.backend.config;

import com.plaid.client.ApiClient;
import com.plaid.client.request.PlaidApi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;


@Configuration
public class PlaidConfig {

    @Bean
    public PlaidApi plaidConfig(
        @Value("${plaid.client-id}") String clientId,
        @Value("${plaid.secret}") String secret,
        @Value("${plaid.environment}") String environment
    ) {

        HashMap<String, String> cred = new HashMap<>();
        cred.put("client_id", clientId);
        cred.put("secret", secret);

        String baseurl = switch (environment) {
            case "SANDBOX" -> ApiClient.Sandbox;
            case "PRODUCTION" -> ApiClient.Production;
            default -> throw new IllegalArgumentException("Invalid PLAID environment");
        };

        ApiClient plaidClient = new ApiClient(cred);
        plaidClient.setPlaidAdapter(baseurl);

        return plaidClient.createService(PlaidApi.class);
    }
}
