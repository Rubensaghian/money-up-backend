package com.moneyup.backend.service;

import com.moneyup.backend.Repository.UserRepository;
import com.moneyup.backend.User;
import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
public class PlaidService {

    private final PlaidApi plaidClient;
    private final UserRepository userRepository;

    public PlaidService(PlaidApi plaidClient, UserRepository userRepository) {
        this.plaidClient = plaidClient;
        this.userRepository = userRepository;
    }

    public String createLinkToken(String username) throws IOException {

        Optional<User> user = userRepository.findByUsername(username);

        if (user.isEmpty()) {
            throw new IllegalArgumentException("Username not found");
        }

        LinkTokenCreateRequestUser linkUser = new LinkTokenCreateRequestUser()
                .clientUserId(user.get().getId().toString());

        LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                .user(linkUser)
                .clientName("MoneyUp")
                .products(List.of(Products.TRANSACTIONS))
                .countryCodes(List.of(CountryCode.US))
                .language("en");

        Response<LinkTokenCreateResponse> response =
                plaidClient.linkTokenCreate(request).execute();

        LinkTokenCreateResponse body = response.body();

        if (!response.isSuccessful() || body == null) {
            throw new IOException("Plaid Token Creation failed with status: " + response.code());
        }
        return body.getLinkToken();
    }

    public String swapPublicToken(String publicToken) throws IOException {
        ItemPublicTokenExchangeRequest request = new ItemPublicTokenExchangeRequest()
                .publicToken(publicToken);

        Response<ItemPublicTokenExchangeResponse> response =
                plaidClient.itemPublicTokenExchange(request).execute();

        ItemPublicTokenExchangeResponse body = response.body();
        if (!response.isSuccessful() || body == null) {
            throw new IOException("Plaid Token exchange failed with status: " + response.code());
        }
        return body.getAccessToken();
    }
}
