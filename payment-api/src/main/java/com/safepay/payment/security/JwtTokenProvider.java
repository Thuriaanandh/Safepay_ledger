package com.safepay.payment.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;
    private final String issuer;

    public JwtTokenProvider(
        ResourceLoader resourceLoader,
        @Value("${safepay.jwt.public-key-path:classpath:jwt/jwt-public.pem}") String publicKeyPath,
        @Value("${safepay.jwt.private-key-path:classpath:jwt/jwt-private.pem}") String privateKeyPath,
        @Value("${safepay.jwt.issuer:safepay-auth}") String issuer
    ) {
        this.publicKey = RsaKeyHelper.loadPublicKey(publicKeyPath, resourceLoader);
        this.privateKey = RsaKeyHelper.loadPrivateKey(privateKeyPath, resourceLoader);
        this.issuer = issuer;
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    public String generateToken(String subject, String scope, long validitySeconds) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(validitySeconds);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
            .subject(subject)
            .issuer(issuer)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiry))
            .claim("scope", scope)
            .build();

        SignedJWT signedJWT = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("safepay-key-1").build(),
            claimsSet
        );

        try {
            signedJWT.sign(new RSASSASigner(privateKey));
            return signedJWT.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign RS256 JWT token", e);
        }
    }
}
