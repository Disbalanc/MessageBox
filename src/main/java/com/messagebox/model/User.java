package com.messagebox.model;

public class User {
    private String username;
    private String passwordHash;
    private long createdAt;

    public User() {}

    public User(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.createdAt = System.currentTimeMillis();
    }

    public String getUsername()     { return username; }
    public String getPasswordHash(){ return passwordHash; }
    public long getCreatedAt()     { return createdAt; }
}