package com.moneyup.backend.controller;


import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PlaidController {

    @PostMapping("/generate_link_token")
    public Map<String,String> generateLinkToken() {
        return Map.of("message", "backend route");
    }

}
