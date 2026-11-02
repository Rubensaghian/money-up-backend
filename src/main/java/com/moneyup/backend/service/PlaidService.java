package com.moneyup.backend.service;

import com.moneyup.backend.PlaidConnection;
import com.moneyup.backend.Repository.PlaidConnectionRepository;
import com.moneyup.backend.Repository.UserRepository;
import com.moneyup.backend.User;
import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
public class PlaidService {

    private final PlaidApi plaidClient;
    private final UserRepository userRepository;
    private final PlaidConnectionRepository plaidConnectionRepository;
    private final TextEncryptor tokenEncryptor;

    public PlaidService(
            PlaidApi plaidClient,
            UserRepository userRepository,
            PlaidConnectionRepository plaidConnectionRepository,
            TextEncryptor tokenEncryptor
    ) {
        this.plaidClient = plaidClient;
        this.userRepository = userRepository;
        this.plaidConnectionRepository = plaidConnectionRepository;
        this.tokenEncryptor = tokenEncryptor;
    }

    public String createLinkToken(String username) throws IOException {
        User user = getUser(username);

        LinkTokenCreateRequestUser linkUser = new LinkTokenCreateRequestUser()
                .clientUserId(user.getId().toString());

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

    public String swapPublicToken(String publicToken, String username) throws IOException {
        User user = getUser(username);

        ItemPublicTokenExchangeRequest request = new ItemPublicTokenExchangeRequest()
                .publicToken(publicToken);

        Response<ItemPublicTokenExchangeResponse> response =
                plaidClient.itemPublicTokenExchange(request).execute();

        ItemPublicTokenExchangeResponse body = response.body();

        if (!response.isSuccessful() || body == null) {
            throw new IOException("Plaid Token exchange failed with status: " + response.code());
        }

        String accessToken = body.getAccessToken();
        String itemId = body.getItemId();

        PlaidConnection plaidConnection = new PlaidConnection();
        plaidConnection.setUserId(user.getId());
        plaidConnection.setItemId(itemId);
        plaidConnection.setAccessTokenEncrypted(tokenEncryptor.encrypt(accessToken));


        PlaidConnection saved = plaidConnectionRepository.save(plaidConnection);


        return saved.getId().toString();
    }

    User getUser(String username) throws IllegalArgumentException {
        Optional<User> user = userRepository.findByUsername(username);
        if (user.isEmpty()) {
            throw new IllegalArgumentException("Username not found");
        }

        return user.get();
    }
}
