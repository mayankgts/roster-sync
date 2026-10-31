package com.moveinsync.roster_sync.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    static final String ISSUER = "roster-sync";

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants/{tenantId}/employee-events")
                                .access(AuthorizationManagers.allOf(sameTenant(), role("INTEGRATION")))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/tenants/{tenantId}/employees/{employeeId}/transport")
                                .access(AuthorizationManagers.allOf(sameTenant(), role("ADMIN")))
                        .requestMatchers(HttpMethod.GET, "/api/v1/tenants/{tenantId}/employees/{employeeId}")
                                .access(AuthorizationManagers.allOf(sameTenant(),
                                        AuthorizationManagers.anyOf(role("ADMIN", "OPERATOR"), selfEmployee())))
                        .requestMatchers("/api/v1/tenants/{tenantId}/reconciliations/**")
                                .access(AuthorizationManagers.allOf(sameTenant(), role("OPERATOR")))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter())));
        return http.build();
    }

    @Bean
    SecretKey jwtKey(SecurityProperties props) {
        byte[] bytes = props.jwtSecret() == null ? new byte[0] : props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("roster.security.jwt-secret must be at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));  // checks exp/nbf + issuer
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** The token's "role" claim becomes a Spring authority, e.g. ROLE_ADMIN. */
    private static JwtAuthenticationConverter authenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            Collection<GrantedAuthority> authorities = role == null
                    ? List.of()
                    : List.of(new SimpleGrantedAuthority("ROLE_" + role));
            return authorities;
        });
        return converter;
    }

    /** The {tenantId} in the URL must equal the token's tenant claim. */
    private static AuthorizationManager<RequestAuthorizationContext> sameTenant() {
        return (authentication, context) -> {
            String pathTenant = context.getVariables().get("tenantId");
            String tokenTenant = claim(authentication.get(), "tenant");
            return new AuthorizationDecision(pathTenant != null && pathTenant.equals(tokenTenant));
        };
    }

    /** An EMPLOYEE may only address their own record: {employeeId} must equal the token subject. */
    private static AuthorizationManager<RequestAuthorizationContext> selfEmployee() {
        return (authentication, context) -> {
            Authentication auth = authentication.get();
            boolean isEmployee = auth.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_EMPLOYEE".equals(a.getAuthority()));
            String pathEmployee = context.getVariables().get("employeeId");
            return new AuthorizationDecision(isEmployee && pathEmployee != null && pathEmployee.equals(auth.getName()));
        };
    }

    private static AuthorizationManager<RequestAuthorizationContext> role(String... roles) {
        return AuthorityAuthorizationManager.hasAnyRole(roles);
    }

    private static String claim(Authentication auth, String name) {
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString(name) : null;
    }
}