package com.worldmind.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtTokenService {

    private final SecretKey signingKey;
    private final int expirationSeconds;

    public JwtTokenService(
            @Value("${worldmind.security.jwt.secret}") String secret,
            @Value("${worldmind.security.jwt.expiration-seconds:600}") int expirationSeconds) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    public String generateToken(String missionId, String taskId, String agentType) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationSeconds * 1000L);

        return Jwts.builder()
                .subject(missionId)
                .claim("taskId", taskId)
                .claim("agentType", agentType)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
