package br.com.vida.assinador.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class LocalFileSecurity {
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE =
            PosixFilePermissions.fromString("rw-------");

    private LocalFileSecurity() {
    }

    public static void createPrivateDirectories(Path directory) throws IOException {
        Files.createDirectories(directory);
        applyIfSupported(directory, PRIVATE_DIRECTORY);
    }

    public static void makePrivateFile(Path file) throws IOException {
        applyIfSupported(file, PRIVATE_FILE);
    }

    private static void applyIfSupported(Path path, Set<PosixFilePermission> permissions) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(
                path,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (view != null) {
            Files.setPosixFilePermissions(path, permissions);
        }
    }
}
