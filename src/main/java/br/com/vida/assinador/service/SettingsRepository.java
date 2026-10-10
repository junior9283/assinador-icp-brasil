package br.com.vida.assinador.service;

import br.com.vida.assinador.model.AppSettings;
import br.com.vida.assinador.model.BatchCorner;
import br.com.vida.assinador.model.TsaConfig;
import br.com.vida.assinador.security.LocalFileSecurity;
import br.com.vida.assinador.security.VaultService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class SettingsRepository {
    private static final Path TSA_PATH = Path.of("settings", "tsa.properties.enc");
    private final VaultService vault;

    public SettingsRepository(VaultService vault) {
        this.vault = vault;
    }

    public AppSettings loadSettings() {
        Path path = settingsPath();
        if (!Files.isRegularFile(path)) {
            return AppSettings.defaults();
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            AppSettings defaults = AppSettings.defaults();
            return new AppSettings(
                    Boolean.parseBoolean(properties.getProperty("darkMode", Boolean.toString(defaults.darkMode()))),
                    BatchCorner.valueOf(properties.getProperty("batchCorner", defaults.batchCorner().name()))
            );
        } catch (Exception e) {
            return AppSettings.defaults();
        }
    }

    public void saveSettings(AppSettings settings) {
        Properties properties = new Properties();
        properties.setProperty("darkMode", Boolean.toString(settings.darkMode()));
        properties.setProperty("batchCorner", settings.batchCorner().name());

        Path path = settingsPath();
        try {
            LocalFileSecurity.createPrivateDirectories(path.getParent());
            try (OutputStream output = Files.newOutputStream(path)) {
                properties.store(output, "ASSINADOR settings");
            }
            LocalFileSecurity.makePrivateFile(path);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível salvar as configurações.", e);
        }
    }

    public TsaConfig loadTsa() {
        Path encrypted = vault.root().resolve(TSA_PATH);
        if (!Files.isRegularFile(encrypted)) {
            return TsaConfig.disabled();
        }
        byte[] data = vault.readEncrypted(TSA_PATH);
        try {
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(data));
            return new TsaConfig(
                    Boolean.parseBoolean(properties.getProperty("enabled", "false")),
                    properties.getProperty("url", ""),
                    properties.getProperty("username", ""),
                    properties.getProperty("password", "")
            );
        } catch (IOException e) {
            throw new IllegalStateException("Configuração de TSA inválida.", e);
        } finally {
            java.util.Arrays.fill(data, (byte) 0);
        }
    }

    public void saveTsa(TsaConfig config) {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(config.enabled()));
        properties.setProperty("url", config.url());
        properties.setProperty("username", config.username());
        properties.setProperty("password", config.password());

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            properties.store(output, "ASSINADOR TSA");
            byte[] data = output.toByteArray();
            try {
                vault.writeEncrypted(TSA_PATH, data);
            } finally {
                java.util.Arrays.fill(data, (byte) 0);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível salvar a configuração da TSA.", e);
        }
    }

    private Path settingsPath() {
        return vault.root().resolve("settings").resolve("app.properties");
    }
}
