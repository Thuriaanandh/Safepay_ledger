package com.safepay.payment.security;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class RsaKeyHelper {

    private RsaKeyHelper() {}

    public static RSAPublicKey loadPublicKey(String location, ResourceLoader resourceLoader) {
        try {
            Resource resource = resourceLoader.getResource(location);
            try (InputStream is = resource.getInputStream()) {
                String key = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                String publicKeyPEM = key
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");

                byte[] encoded = Base64.getDecoder().decode(publicKeyPEM);
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encoded);
                return (RSAPublicKey) keyFactory.generatePublic(keySpec);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load RSA public key from " + location, e);
        }
    }

    public static RSAPrivateKey loadPrivateKey(String location, ResourceLoader resourceLoader) {
        try {
            Resource resource = resourceLoader.getResource(location);
            try (InputStream is = resource.getInputStream()) {
                String key = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                String privateKeyPEM = key
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");

                byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
                return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load RSA private key from " + location, e);
        }
    }
}
