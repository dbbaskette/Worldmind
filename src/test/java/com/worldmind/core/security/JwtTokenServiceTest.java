package com.worldmind.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenServiceTest {

    private static final String SECRET = "worldmind-test-secret-must-be-at-least-32-bytes-long";
    private static final int EXPIRATION_SECONDS = 600;

    private JwtTokenService service;

    @BeforeEach
    void setUp() {
        service = new JwtTokenService(SECRET, EXPIRATION_SECONDS);
    }

    @Nested
    @DisplayName("generateToken")
    class GenerateTokenTests {

        @Test
        @DisplayName("generates token containing expected claims")
        void generatesTokenWithExpectedClaims() {
            String token = service.generateToken("M-001", "DIR-001", "FORGE");

            Claims claims = service.validateToken(token);
            assertEquals("M-001", claims.getSubject());
            assertEquals("DIR-001", claims.get("directiveId", String.class));
            assertEquals("FORGE", claims.get("centurionType", String.class));
            assertNotNull(claims.getIssuedAt());
            assertNotNull(claims.getExpiration());
        }

        @Test
        @DisplayName("round-trip: generate then validate returns consistent claims")
        void roundTripGenerateAndValidate() {
            String token = service.generateToken("M-042", "DIR-099", "GAUNTLET");

            Claims claims = service.validateToken(token);
            assertEquals("M-042", claims.getSubject());
            assertEquals("DIR-099", claims.get("directiveId", String.class));
            assertEquals("GAUNTLET", claims.get("centurionType", String.class));
        }
    }

    @Nested
    @DisplayName("validateToken")
    class ValidateTokenTests {

        @Test
        @DisplayName("throws on expired token")
        void throwsOnExpiredToken() {
            // Create a service with 0-second expiration to produce an already-expired token
            JwtTokenService shortLivedService = new JwtTokenService(SECRET, 0);
            String token = shortLivedService.generateToken("M-001", "DIR-001", "FORGE");

            assertThrows(ExpiredJwtException.class, () -> service.validateToken(token));
        }

        @Test
        @DisplayName("throws on invalid signature")
        void throwsOnInvalidSignature() {
            String token = service.generateToken("M-001", "DIR-001", "FORGE");

            // Create a service with a different secret
            JwtTokenService otherService = new JwtTokenService(
                    "a-completely-different-secret-key-at-least-32-bytes", EXPIRATION_SECONDS);

            assertThrows(SignatureException.class, () -> otherService.validateToken(token));
        }

        @Test
        @DisplayName("throws on malformed token")
        void throwsOnMalformedToken() {
            assertThrows(Exception.class, () -> service.validateToken("not.a.valid.token"));
        }
    }
}
