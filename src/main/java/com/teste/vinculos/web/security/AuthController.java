package com.teste.vinculos.web.security;

import com.teste.vinculos.domain.InvalidDataException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

import java.time.Instant;

@RestController
@Profile("!seed")
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Emissao de token JWT para acessar a API")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokens;

    public AuthController(AuthenticationManager authenticationManager, TokenService tokens) {
        this.authenticationManager = authenticationManager;
        this.tokens = tokens;
    }

    @PostMapping("/token")
    @Operation(summary = "Autentica e emite um JWT")
    public TokenResponse token(@RequestBody LoginRequest request) {
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw new InvalidDataException("Username and password are required");
        }
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        var token = tokens.issue(authentication);
        return new TokenResponse(token.value(), "Bearer", token.expiresAt());
    }

    public record LoginRequest(String username, String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt) {
    }
}
