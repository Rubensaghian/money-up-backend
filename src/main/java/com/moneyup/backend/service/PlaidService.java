package com.moneyup.backend.service;

import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.util.List;

@Service
public class PlaidService {

    private final PlaidApi plaidClient;

    public PlaidService(PlaidApi plaidClient) {
        this.plaidClient = plaidClient;
    }

    public String createLinkToken() throws IOException {
        LinkTokenCreateRequestUser user = new LinkTokenCreateRequestUser()
                .clientUserId("MoneyUp-rubs");

        LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                .user(user)
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
