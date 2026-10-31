package com.moveinsync.roster_sync.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "roster.security")
public record SecurityProperties(String jwtSecret, Duration tokenTtl, List<DemoUser> users) {

    public SecurityProperties {
        if (tokenTtl == null) {
            tokenTtl = Duration.ofMinutes(60);
        }
        if (users == null) {
            users = List.of();
        }
    }

    public record DemoUser(String username, String passwordHash, String role, String tenant) {
        @Override
        public String toString() {
            return "DemoUser[" + username + "]";  // keep hashes out of logs
        }
    }
}