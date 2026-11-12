package com.safepay.payment.controller;

import com.safepay.payment.security.JwtTokenProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Self-Issued RS256 JWT Token Provider")
public class AuthController {

    private final JwtTokenProvider tokenProvider;

    public AuthController(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @GetMapping("/token")
    @Operation(summary = "Generate a self-issued RS256 JWT token for testing and local dev")
    public ResponseEntity<Map<String, Object>> getToken(
        @RequestParam(defaultValue = "user-alice") String subject,
        @RequestParam(defaultValue = "transfers:write") String scope
    ) {
        long validitySeconds = 3600;
        String token = tokenProvider.generateToken(subject, scope, validitySeconds);

        return ResponseEntity.ok(Map.of(
            "access_token", token,
            "token_type", "Bearer",
            "expires_in", validitySeconds,
            "scope", scope
        ));
    }
}
