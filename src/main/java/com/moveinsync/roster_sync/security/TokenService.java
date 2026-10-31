package com.moveinsync.roster_sync.security;

import com.moveinsync.roster_sync.security.SecurityProperties.DemoUser;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Demo token issuer. In production an identity provider (Keycloak, Okta, Azure AD)
 * issues tokens and this service would not exist; the API side stays the same.
 */
@Service
public class TokenService {

    // Checked when the username is unknown, so a wrong username takes as long as a
    // wrong password and response time does not reveal which usernames exist.
    private static final String DUMMY_HASH = "$2b$10$N/ds51Mdiz8K3HfMTdvBmuNs7Qxox6yHwtBo99jIB9QBV6ydQCkmi";

    private final Map<String, DemoUser> users;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final Duration ttl;

    public TokenService(SecurityProperties props, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder) {
        this.users = props.users().stream().collect(Collectors.toMap(DemoUser::username, Function.identity()));
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.ttl = props.tokenTtl();
    }

    public Optional<IssuedToken> issue(String username, String password) {
        DemoUser user = username == null ? null : users.get(username);
        boolean passwordOk = passwordEncoder.matches(password == null ? "" : password,
                user == null ? DUMMY_HASH : user.passwordHash());
        if (user == null || !passwordOk) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(SecurityConfig.ISSUER)
                .subject(user.username())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("role", user.role())
                .claim("tenant", user.tenant())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return Optional.of(new IssuedToken(token, "Bearer", ttl.toSeconds()));
    }

    public record IssuedToken(String accessToken, String tokenType, long expiresIn) {
    }
}