package com.moneyup.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

@Configuration
public class TokenEncryptionConfig {

    @Bean
    public TextEncryptor tokenEncryptor(
        @Value("${moneyup.token-encryption.secret}") String secret,
        @Value("${moneyup.token-encryption.salt}") String salt
    ) {
        return Encryptors.delux(secret, salt);
    }
}
