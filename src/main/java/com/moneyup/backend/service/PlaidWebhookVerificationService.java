package com.moneyup.backend.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.plaid.client.model.JWKPublicKey;
import com.plaid.client.model.WebhookVerificationKeyGetRequest;
import com.plaid.client.model.WebhookVerificationKeyGetResponse;
import com.plaid.client.request.PlaidApi;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

@Service
public class PlaidWebhookVerificationService {
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final PlaidApi plaidApi;

    public PlaidWebhookVerificationService(PlaidApi plaidApi) {
        this.plaidApi = plaidApi;
    }

    public boolean verify(String token, byte[] rawBody) throws IOException {
        if (token == null || token.isBlank() || rawBody == null) {
            return false;
        }

        try {
            SignedJWT jwt = SignedJWT.parse(token);
            String keyId = jwt.getHeader().getKeyID();
            if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())
                    || keyId == null || keyId.isBlank()) {
                return false;
            }

            JWKPublicKey key = getVerificationKey(keyId);
            if (key == null || !keyId.equals(key.getKid())
                    || !"ES256".equals(key.getAlg()) || !"EC".equals(key.getKty())
                    || !"P-256".equals(key.getCrv()) || !"sig".equals(key.getUse())
                    || key.getX() == null || key.getY() == null
                    || (key.getExpiredAt() != null
                    && key.getExpiredAt() <= Instant.now().getEpochSecond())) {
                return false;
            }

            ECKey publicKey = new ECKey.Builder(Curve.P_256,
                    new Base64URL(key.getX()), new Base64URL(key.getY())).build();
            if (!jwt.verify(new ECDSAVerifier(publicKey))) {
                return false;
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date issuedAt = claims.getIssueTime();
            if (issuedAt == null || !isValidIat(issuedAt.toInstant().getEpochSecond(), MAX_AGE, CLOCK_SKEW)) {
                return false;
            }

            String claimedHash = claims.getStringClaim("request_body_sha256");
            if (claimedHash == null || !claimedHash.matches("[0-9a-fA-F]{64}")) {
                return false;
            }

            return MessageDigest.isEqual(sha256(rawBody), HexFormat.of().parseHex(claimedHash));
        } catch (ParseException | JOSEException | IllegalArgumentException exception) {
            return false;
        }
    }

    private JWKPublicKey getVerificationKey(String keyId) throws IOException {
        Response<WebhookVerificationKeyGetResponse> response = plaidApi.webhookVerificationKeyGet(
                new WebhookVerificationKeyGetRequest().keyId(keyId)).execute();

        if (response.code() == 400 || response.code() == 404) {
            return null;
        }
        if (!response.isSuccessful() || response.body() == null || response.body().getKey() == null) {
            throw new IOException("Could not retrieve Plaid webhook verification key. HTTP " + response.code());
        }
        return response.body().getKey();
    }

    public static boolean isValidIat(long iatSeconds, Duration maxAge, Duration clockSkew) {
        Instant issuedAt = Instant.ofEpochSecond(iatSeconds);
        Instant now = Instant.now();
        return !issuedAt.isAfter(now.plus(clockSkew)) && !issuedAt.isBefore(now.minus(maxAge));
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime must support SHA-256", exception);
        }
    }
}
