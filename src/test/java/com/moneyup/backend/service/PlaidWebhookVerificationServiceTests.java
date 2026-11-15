package com.moneyup.backend.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.plaid.client.model.JWKPublicKey;
import com.plaid.client.model.WebhookVerificationKeyGetRequest;
import com.plaid.client.model.WebhookVerificationKeyGetResponse;
import com.plaid.client.request.PlaidApi;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlaidWebhookVerificationServiceTests {
    private static final byte[] BODY = "{\n  \"item_id\": \"item-123\"\n}".getBytes(StandardCharsets.UTF_8);
    private PlaidApi api;
    private PlaidWebhookVerificationService service;
    private ECKey signingKey;
    private JWKPublicKey publicKey;
    private Call<WebhookVerificationKeyGetResponse> keyCall;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        api = mock(PlaidApi.class);
        service = new PlaidWebhookVerificationService(api);
        signingKey = new ECKeyGenerator(Curve.P_256).keyID("key-123").generate();
        publicKey = new JWKPublicKey().alg("ES256").crv("P-256").kty("EC").use("sig")
                .kid(signingKey.getKeyID()).x(signingKey.getX().toString()).y(signingKey.getY().toString());
        keyCall = mock(Call.class);
        when(keyCall.execute()).thenReturn(Response.success(new WebhookVerificationKeyGetResponse().key(publicKey)));
        when(api.webhookVerificationKeyGet(any())).thenReturn(keyCall);
    }

    @Test
    void verifiesARealSignatureAndRequestsTheKeyNamedInTheHeader() throws Exception {
        assertTrue(service.verify(signed(claims(Instant.now(), hash(BODY))), BODY));
        ArgumentCaptor<WebhookVerificationKeyGetRequest> request =
                ArgumentCaptor.forClass(WebhookVerificationKeyGetRequest.class);
        verify(api).webhookVerificationKeyGet(request.capture());
        assertEquals("key-123", request.getValue().getKeyId());
    }

    @Test
    void rejectsAChangedBodyEvenWhenItsJsonMeaningIsTheSame() throws Exception {
        byte[] reformatted = "{\"item_id\":\"item-123\"}".getBytes(StandardCharsets.UTF_8);
        assertFalse(service.verify(signed(claims(Instant.now(), hash(BODY))), reformatted));
    }

    @Test
    void rejectsASignatureFromADifferentPrivateKey() throws Exception {
        ECKey otherKey = new ECKeyGenerator(Curve.P_256).generate();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("key-123").build(),
                claims(Instant.now(), hash(BODY)));
        jwt.sign(new ECDSASigner(otherKey));
        assertFalse(service.verify(jwt.serialize(), BODY));
    }

    @Test
    void rejectsMissingAndMalformedTokensWithoutCallingPlaid() throws Exception {
        assertFalse(service.verify(null, BODY));
        assertFalse(service.verify(" ", BODY));
        assertFalse(service.verify("not-a-jwt", BODY));
        verifyNoInteractions(api);
    }

    @Test
    void rejectsAnUnexpectedAlgorithmBeforeLookingUpAKey() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("key-123").build(),
                claims(Instant.now(), hash(BODY)));
        jwt.sign(new MACSigner(new byte[32]));
        assertFalse(service.verify(jwt.serialize(), BODY));
        verifyNoInteractions(api);
    }

    @Test
    void rejectsAMissingKeyIdBeforeCallingPlaid() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), claims(Instant.now(), hash(BODY)));
        jwt.sign(new ECDSASigner(signingKey));
        assertFalse(service.verify(jwt.serialize(), BODY));
        verifyNoInteractions(api);
    }

    @Test
    void rejectsAnOldOrFutureTimestamp() throws Exception {
        assertFalse(service.verify(signed(claims(Instant.now().minusSeconds(360), hash(BODY))), BODY));
        assertFalse(service.verify(signed(claims(Instant.now().plusSeconds(120), hash(BODY))), BODY));
    }

    @Test
    void rejectsMissingOrMalformedClaims() throws Exception {
        assertFalse(service.verify(signed(new JWTClaimsSet.Builder()
                .claim("request_body_sha256", hash(BODY)).build()), BODY));
        assertFalse(service.verify(signed(new JWTClaimsSet.Builder().issueTime(new Date()).build()), BODY));
        assertFalse(service.verify(signed(claims(Instant.now(), "not-hex")), BODY));
        assertFalse(service.verify(signed(new JWTClaimsSet.Builder().issueTime(new Date())
                .claim("request_body_sha256", 123).build()), BODY));
    }

    @Test
    void rejectsExpiredOrMismatchedPublicKeys() throws Exception {
        String token = signed(claims(Instant.now(), hash(BODY)));
        publicKey.expiredAt(Math.toIntExact(Instant.now().minusSeconds(1).getEpochSecond()));
        assertFalse(service.verify(token, BODY));
        publicKey.expiredAt(null).kid("another-key");
        assertFalse(service.verify(token, BODY));
        publicKey.kid("key-123").crv("P-384");
        assertFalse(service.verify(token, BODY));
    }

    @Test
    void rejectsUnknownKeys() throws Exception {
        when(keyCall.execute()).thenReturn(Response.error(400,
                ResponseBody.create("{}", MediaType.get("application/json"))));
        assertFalse(service.verify(signed(claims(Instant.now(), hash(BODY))), BODY));
    }

    @Test
    void reportsPlaidOutagesAsAnOperationalFailure() throws Exception {
        String token = signed(claims(Instant.now(), hash(BODY)));
        when(keyCall.execute()).thenThrow(new IOException("network unavailable"));
        assertThrows(IOException.class, () -> service.verify(token, BODY));
        doReturn(Response.error(503, ResponseBody.create("{}", MediaType.get("application/json"))))
                .when(keyCall).execute();
        assertThrows(IOException.class, () -> service.verify(token, BODY));
    }

    @Test
    void reportsAnEmptyKeyResponseAsAnOperationalFailure() throws Exception {
        String token = signed(claims(Instant.now(), hash(BODY)));
        when(keyCall.execute()).thenReturn(Response.success(new WebhookVerificationKeyGetResponse()));
        assertThrows(IOException.class, () -> service.verify(token, BODY));
    }

    private String signed(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new ECDSASigner(signingKey));
        return jwt.serialize();
    }

    private static JWTClaimsSet claims(Instant issuedAt, String hash) {
        return new JWTClaimsSet.Builder().issueTime(Date.from(issuedAt))
                .claim("request_body_sha256", hash).build();
    }

    private static String hash(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }
}
