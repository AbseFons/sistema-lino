package com.tienda.inventario.domain;

public record Session(long userId, String displayName, String login, String role) {
    public boolean isAdmin() { return "ADMIN".equals(role); }
}
