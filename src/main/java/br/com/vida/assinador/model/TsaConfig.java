package br.com.vida.assinador.model;

public record TsaConfig(boolean enabled, String url, String username, String password) {
    public TsaConfig {
        url = url == null ? "" : url.trim();
        username = username == null ? "" : username;
        password = password == null ? "" : password;
    }

    public static TsaConfig disabled() {
        return new TsaConfig(false, "", "", "");
    }

    public boolean isUsable() {
        return enabled && !url.isBlank();
    }
}
