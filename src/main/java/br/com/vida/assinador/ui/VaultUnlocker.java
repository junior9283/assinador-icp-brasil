package br.com.vida.assinador.ui;

import br.com.vida.assinador.security.VaultException;
import br.com.vida.assinador.security.VaultService;

import java.util.Arrays;
import java.util.Optional;

public final class VaultUnlocker {
    private VaultUnlocker() {
    }

    public static boolean unlock(VaultService vault) {
        if (vault.hasAutomaticUnlock()) {
            try {
                vault.unlockAutomatic();
                return true;
            } catch (VaultException e) {
                UiDialogs.error("Cofre", e.getMessage());
                return false;
            }
        }

        if (!vault.isInitialized()) {
            try {
                vault.createAutomatic();
                return true;
            } catch (VaultException e) {
                UiDialogs.error("Cofre", e.getMessage());
                return false;
            }
        }

        return migrateLegacyVault(vault);
    }

    private static boolean migrateLegacyVault(VaultService vault) {
        while (true) {
            Optional<char[]> password = UiDialogs.password(
                    "Atualizar proteção local",
                    "Informe sua senha mestra uma última vez para ativar o desbloqueio automático."
            );
            if (password.isEmpty()) {
                return false;
            }
            char[] value = password.get();
            try {
                vault.unlock(value);
                vault.enableAutomaticUnlock();
                return true;
            } catch (VaultException e) {
                UiDialogs.error("Cofre", e.getMessage());
            } finally {
                Arrays.fill(value, '\0');
            }
        }
    }
}
