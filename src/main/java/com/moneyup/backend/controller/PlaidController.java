package com.moneyup.backend.controller;


import com.moneyup.backend.service.PlaidService;
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
    public Map<String,String> generateLinkToken() throws IOException {
       String linkToken = plaidService.createLinkToken();

       return Map.of("link_token", linkToken);
    }

    @PostMapping("swap_public_token")
    public Map<String,String> swapPublicToken(@RequestBody String publicToken) throws IOException {
        String accessToken = plaidService.swapPublicToken(publicToken);
        return Map.of("access_token", accessToken);
    }

}
