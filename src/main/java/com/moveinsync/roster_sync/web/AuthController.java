package com.moveinsync.roster_sync.web;

import com.moveinsync.roster_sync.security.TokenService;
import com.moveinsync.roster_sync.security.TokenService.IssuedToken;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final TokenService tokens;

    public AuthController(TokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/api/v1/auth/token")
    public ResponseEntity<Object> token(@RequestBody LoginRequest request) {
        Optional<IssuedToken> token = tokens.issue(request.username(), request.password());
        if (token.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        }
        return ResponseEntity.ok(token.get());
    }
}