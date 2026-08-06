package com.theosfera.proxy.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileBackendControlSecretProviderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void loadsExactAuthorizedBackendSecretSet() throws Exception {
        byte[] lobbySecret = secret((byte) 1);
        byte[] skyblockSecret = secret((byte) 2);
        Path file = writeSecrets(
                "lobby-1=" + encode(lobbySecret),
                "skyblock-1=" + encode(skyblockSecret)
        );

        try (FileBackendControlSecretProvider provider =
                     FileBackendControlSecretProvider.load(
                             file,
                             Set.of("lobby-1", "skyblock-1")
                     )) {
            assertEquals(2, provider.size());
            assertArrayEquals(
                    lobbySecret,
                    provider.findSecret("lobby-1").orElseThrow()
            );
            assertArrayEquals(
                    skyblockSecret,
                    provider.findSecret("skyblock-1").orElseThrow()
            );
        }
    }

    @Test
    void returnsDefensiveSecretCopies() throws Exception {
        byte[] expected = secret((byte) 7);
        Path file = writeSecrets(
                "lobby-1=" + encode(expected)
        );

        try (FileBackendControlSecretProvider provider =
                     FileBackendControlSecretProvider.load(
                             file,
                             Set.of("lobby-1")
                     )) {
            byte[] first = provider.findSecret(
                    "lobby-1"
            ).orElseThrow();
            first[0] = 99;

            assertArrayEquals(
                    expected,
                    provider.findSecret("lobby-1").orElseThrow()
            );
        }
    }

    @Test
    void closeClearsLoadedSecrets() throws Exception {
        Path file = writeSecrets(
                "lobby-1=" + encode(secret((byte) 3))
        );
        FileBackendControlSecretProvider provider =
                FileBackendControlSecretProvider.load(
                        file,
                        Set.of("lobby-1")
                );

        provider.close();

        assertEquals(0, provider.size());
        assertTrue(provider.findSecret("lobby-1").isEmpty());
    }

    @Test
    void rejectsMissingExpectedBackendSecret() throws Exception {
        Path file = writeSecrets(
                "lobby-1=" + encode(secret((byte) 1))
        );

        assertThrows(
                IllegalStateException.class,
                () -> FileBackendControlSecretProvider.load(
                        file,
                        Set.of("lobby-1", "skyblock-1")
                )
        );
    }

    @Test
    void rejectsSecretForUnauthorizedBackend() throws Exception {
        Path file = writeSecrets(
                "unknown-1=" + encode(secret((byte) 1))
        );

        assertThrows(
                IllegalStateException.class,
                () -> FileBackendControlSecretProvider.load(
                        file,
                        Set.of("lobby-1")
                )
        );
    }

    @Test
    void rejectsDuplicateBackendSecret() throws Exception {
        Path file = writeSecrets(
                "lobby-1=" + encode(secret((byte) 1)),
                "lobby-1=" + encode(secret((byte) 2))
        );

        assertThrows(
                IllegalStateException.class,
                () -> FileBackendControlSecretProvider.load(
                        file,
                        Set.of("lobby-1")
                )
        );
    }

    @Test
    void rejectsShortDecodedSecret() throws Exception {
        byte[] shortSecret = new byte[31];
        Path file = writeSecrets(
                "lobby-1=" + encode(shortSecret)
        );

        assertThrows(
                IllegalStateException.class,
                () -> FileBackendControlSecretProvider.load(
                        file,
                        Set.of("lobby-1")
                )
        );
    }

    @Test
    void rejectsPaddedOrMalformedBase64UrlSecret() throws Exception {
        Path file = writeSecrets("lobby-1=abcd=");

        assertThrows(
                IllegalStateException.class,
                () -> FileBackendControlSecretProvider.load(
                        file,
                        Set.of("lobby-1")
                )
        );
    }

    private Path writeSecrets(String... lines) throws Exception {
        Path file = tempDirectory.resolve("control-secrets.properties");
        Files.writeString(
                file,
                String.join(System.lineSeparator(), lines)
                        + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
        return file;
    }

    private static byte[] secret(byte value) {
        byte[] secret = new byte[32];
        java.util.Arrays.fill(secret, value);
        return secret;
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value);
    }
}
