package br.com.vida.assinador.security;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

public final class VaultService implements AutoCloseable {
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final int KEY_BITS = 256;
    private static final int KEY_BYTES = KEY_BITS / Byte.SIZE;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte FORMAT_VERSION = 1;
    private static final byte[] VERIFIER = "ASSINADOR-VAULT-V1".getBytes(StandardCharsets.UTF_8);
    private static final String AUTOMATIC_UNLOCK_FILE = "auto-unlock.key";

    private final Path root;
    private final SecureRandom secureRandom = new SecureRandom();
    private byte[] keyMaterial;

    public VaultService(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public boolean isInitialized() {
        return Files.isRegularFile(root.resolve("salt.bin"))
                && Files.isRegularFile(root.resolve("verifier.bin"));
    }

    public boolean hasAutomaticUnlock() {
        return Files.isRegularFile(automaticUnlockPath());
    }

    public void create(char[] masterPassword) {
        requirePassword(masterPassword);
        if (isInitialized()) {
            throw new VaultException("O cofre já foi criado.");
        }

        try {
            byte[] salt = randomBytes(SALT_BYTES);
            byte[] derived = deriveKey(masterPassword, salt);
            try {
                initialize(derived, salt);
            } finally {
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(derived, (byte) 0);
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new VaultException("Não foi possível criar o cofre local.", e);
        }
    }

    public void createAutomatic() {
        if (isInitialized()) {
            throw new VaultException("O cofre já foi criado.");
        }

        byte[] key = randomBytes(KEY_BYTES);
        byte[] salt = randomBytes(SALT_BYTES);
        try {
            initialize(key, salt);
            persistAutomaticUnlockKey(key);
        } catch (IOException | GeneralSecurityException e) {
            close();
            deleteAutomaticInitializationFiles();
            throw new VaultException("Não foi possível criar a proteção local automática.", e);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    public void unlock(char[] masterPassword) {
        requirePassword(masterPassword);
        if (!isInitialized()) {
            throw new VaultException("O cofre ainda não foi criado.");
        }

        try {
            LocalFileSecurity.createPrivateDirectories(root);
            LocalFileSecurity.makePrivateFile(root.resolve("salt.bin"));
            LocalFileSecurity.makePrivateFile(root.resolve("verifier.bin"));
            byte[] salt = Files.readAllBytes(root.resolve("salt.bin"));
            byte[] derived = deriveKey(masterPassword, salt);
            try {
                unlockWithKey(derived, "Senha mestra incorreta.");
            } catch (AEADBadTagException e) {
                throw new VaultException("Senha mestra incorreta.");
            } finally {
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(derived, (byte) 0);
            }
        } catch (IOException | GeneralSecurityException e) {
            if (e instanceof VaultException vaultException) {
                throw vaultException;
            }
            throw new VaultException("Não foi possível desbloquear o cofre.", e);
        }
    }

    public void unlockAutomatic() {
        if (!isInitialized()) {
            throw new VaultException("O cofre ainda não foi criado.");
        }
        Path keyPath = automaticUnlockPath();
        if (!Files.isRegularFile(keyPath)) {
            throw new VaultException("A chave de desbloqueio automático não foi encontrada.");
        }

        byte[] key = null;
        try {
            LocalFileSecurity.createPrivateDirectories(root);
            LocalFileSecurity.makePrivateFile(keyPath);
            key = Files.readAllBytes(keyPath);
            requireKey(key);
            unlockWithKey(key, "A chave de desbloqueio automático é inválida.");
        } catch (AEADBadTagException e) {
            throw new VaultException("A chave de desbloqueio automático é inválida.");
        } catch (IOException | GeneralSecurityException e) {
            if (e instanceof VaultException vaultException) {
                throw vaultException;
            }
            throw new VaultException("Não foi possível desbloquear o cofre automaticamente.", e);
        } finally {
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
        }
    }

    public void enableAutomaticUnlock() {
        requireUnlocked();
        try {
            persistAutomaticUnlockKey(keyMaterial);
        } catch (IOException e) {
            throw new VaultException("Não foi possível ativar o desbloqueio automático.", e);
        }
    }

    public void writeEncrypted(Path relativePath, byte[] clearData) {
        requireUnlocked();
        Path destination = resolveSafe(relativePath);
        byte[] encrypted = encrypt(clearData);
        try {
            LocalFileSecurity.createPrivateDirectories(destination.getParent());
            atomicWrite(destination, encrypted);
        } catch (IOException e) {
            throw new VaultException("Não foi possível gravar dados no cofre.", e);
        } finally {
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    public byte[] readEncrypted(Path relativePath) {
        requireUnlocked();
        Path source = resolveSafe(relativePath);
        try {
            return decrypt(Files.readAllBytes(source));
        } catch (IOException e) {
            throw new VaultException("Não foi possível ler dados do cofre.", e);
        }
    }

    public void delete(Path relativePath) {
        try {
            Files.deleteIfExists(resolveSafe(relativePath));
        } catch (IOException e) {
            throw new VaultException("Não foi possível remover o item do cofre.", e);
        }
    }

    public Path root() {
        return root;
    }

    private byte[] encrypt(byte[] clearData) {
        try {
            return encryptWithKey(clearData, keyMaterial);
        } catch (GeneralSecurityException e) {
            throw new VaultException("Falha ao criptografar os dados.", e);
        }
    }

    private byte[] decrypt(byte[] encryptedData) {
        try {
            return decryptWithKey(encryptedData, keyMaterial);
        } catch (AEADBadTagException e) {
            throw new VaultException("Os dados do cofre não puderam ser autenticados.", e);
        } catch (GeneralSecurityException e) {
            throw new VaultException("Falha ao descriptografar os dados.", e);
        }
    }

    private void initialize(byte[] key, byte[] salt) throws IOException, GeneralSecurityException {
        LocalFileSecurity.createPrivateDirectories(root);
        atomicWrite(root.resolve("salt.bin"), salt);
        atomicWrite(root.resolve("verifier.bin"), encryptWithKey(VERIFIER, key));
        replaceKey(key);
    }

    private void unlockWithKey(byte[] key, String invalidKeyMessage)
            throws IOException, GeneralSecurityException {
        byte[] decrypted = decryptWithKey(Files.readAllBytes(root.resolve("verifier.bin")), key);
        try {
            if (!Arrays.equals(VERIFIER, decrypted)) {
                throw new VaultException(invalidKeyMessage);
            }
            replaceKey(key);
        } finally {
            Arrays.fill(decrypted, (byte) 0);
        }
    }

    private byte[] encryptWithKey(byte[] clearData, byte[] key) throws GeneralSecurityException {
        byte[] iv = randomBytes(IV_BYTES);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(clearData);
        return ByteBuffer.allocate(1 + IV_BYTES + ciphertext.length)
                .put(FORMAT_VERSION)
                .put(iv)
                .put(ciphertext)
                .array();
    }

    private byte[] decryptWithKey(byte[] encryptedData, byte[] key) throws GeneralSecurityException {
        if (encryptedData.length <= 1 + IV_BYTES || encryptedData[0] != FORMAT_VERSION) {
            throw new VaultException("Formato de cofre inválido.");
        }

        ByteBuffer buffer = ByteBuffer.wrap(encryptedData);
        buffer.get();
        byte[] iv = new byte[IV_BYTES];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    private byte[] deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private void persistAutomaticUnlockKey(byte[] key) throws IOException {
        requireKey(key);
        LocalFileSecurity.createPrivateDirectories(root);
        atomicWrite(automaticUnlockPath(), key);
    }

    private Path automaticUnlockPath() {
        return root.resolve(AUTOMATIC_UNLOCK_FILE);
    }

    private void deleteAutomaticInitializationFiles() {
        try {
            Files.deleteIfExists(automaticUnlockPath());
            Files.deleteIfExists(root.resolve("verifier.bin"));
            Files.deleteIfExists(root.resolve("salt.bin"));
        } catch (IOException ignored) {
            // A próxima inicialização informará qualquer estado residual inválido.
        }
    }

    private Path resolveSafe(Path relativePath) {
        if (relativePath.isAbsolute()) {
            throw new VaultException("O caminho do cofre deve ser relativo.");
        }
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new VaultException("Caminho inválido no cofre.");
        }
        return resolved;
    }

    private void atomicWrite(Path destination, byte[] data) throws IOException {
        Path temp = Files.createTempFile(
                destination.getParent(),
                destination.getFileName().toString() + ".",
                ".tmp"
        );
        try {
            LocalFileSecurity.makePrivateFile(temp);
            Files.write(temp, data);
            try {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            LocalFileSecurity.makePrivateFile(destination);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private byte[] randomBytes(int size) {
        byte[] value = new byte[size];
        secureRandom.nextBytes(value);
        return value;
    }

    private void replaceKey(byte[] key) {
        close();
        keyMaterial = key.clone();
    }

    private void requireUnlocked() {
        if (keyMaterial == null) {
            throw new VaultException("O cofre está bloqueado.");
        }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length < 8) {
            throw new VaultException("A senha mestra deve ter pelo menos 8 caracteres.");
        }
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new VaultException("Chave local de proteção inválida.");
        }
    }

    @Override
    public void close() {
        if (keyMaterial != null) {
            Arrays.fill(keyMaterial, (byte) 0);
            keyMaterial = null;
        }
    }
}
