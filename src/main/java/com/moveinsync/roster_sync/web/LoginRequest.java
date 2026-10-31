package com.moveinsync.roster_sync.web;

public record LoginRequest(String username, String password) {
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + "]";  // never print the password
    }
}