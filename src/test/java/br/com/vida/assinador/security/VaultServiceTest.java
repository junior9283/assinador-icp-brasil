package br.com.vida.assinador.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsUnlocksAndEncryptsData() {
        char[] password = "uma-senha-forte".toCharArray();
        byte[] secret = "conteudo sensivel".getBytes();

        try (VaultService vault = new VaultService(tempDir)) {
            vault.create(password);
            vault.writeEncrypted(Path.of("test", "secret.bin"), secret);
        }

        try (VaultService reopened = new VaultService(tempDir)) {
            reopened.unlock(password);
            assertArrayEquals(secret, reopened.readEncrypted(Path.of("test", "secret.bin")));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Test
    void rejectsWrongMasterPassword() {
        try (VaultService vault = new VaultService(tempDir)) {
            vault.create("senha-correta".toCharArray());
        }

        try (VaultService reopened = new VaultService(tempDir)) {
            assertThrows(VaultException.class, () -> reopened.unlock("senha-incorreta".toCharArray()));
        }
    }

    @Test
    void createsAndReopensVaultWithAutomaticUnlock() {
        byte[] secret = "conteudo automatico".getBytes();

        try (VaultService vault = new VaultService(tempDir)) {
            vault.createAutomatic();
            vault.writeEncrypted(Path.of("test", "automatic.bin"), secret);
        }

        try (VaultService reopened = new VaultService(tempDir)) {
            reopened.unlockAutomatic();
            assertArrayEquals(secret, reopened.readEncrypted(Path.of("test", "automatic.bin")));
        }
    }

    @Test
    void enablesAutomaticUnlockForExistingPasswordVault() {
        char[] password = "senha-legada-forte".toCharArray();
        byte[] secret = "conteudo legado".getBytes();

        try (VaultService vault = new VaultService(tempDir)) {
            vault.create(password);
            vault.writeEncrypted(Path.of("test", "legacy.bin"), secret);
            vault.enableAutomaticUnlock();
        }

        try (VaultService reopened = new VaultService(tempDir)) {
            reopened.unlockAutomatic();
            assertArrayEquals(secret, reopened.readEncrypted(Path.of("test", "legacy.bin")));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Test
    void restrictsVaultPermissionsWhenPosixIsAvailable() throws Exception {
        char[] password = "senha-posix-forte".toCharArray();
        Path secret = tempDir.resolve("private").resolve("secret.bin");

        try (VaultService vault = new VaultService(tempDir)) {
            vault.create(password);
            vault.writeEncrypted(Path.of("private", "secret.bin"), new byte[]{1, 2, 3});
        } finally {
            Arrays.fill(password, '\0');
        }

        PosixFileAttributeView view = Files.getFileAttributeView(
                tempDir,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (view == null) {
            return;
        }

        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(tempDir));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(tempDir.resolve("salt.bin")));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(tempDir.resolve("verifier.bin")));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(secret));
    }
}
