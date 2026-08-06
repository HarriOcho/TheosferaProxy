package com.theosfera.proxy.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class FileBackendControlSecretProvider
        implements BackendControlSecretProvider, AutoCloseable {

    private static final int MIN_SECRET_BYTES = 32;
    private static final int MAX_SECRET_BYTES = 128;
    private static final Pattern BACKEND_NAME_PATTERN = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$"
    );
    private static final Pattern BASE64_URL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9_-]+$"
    );

    private final Map<String, byte[]> secrets;

    private FileBackendControlSecretProvider(
            Map<String, byte[]> secrets
    ) {
        this.secrets = secrets;
    }

    public static FileBackendControlSecretProvider load(
            Path secretsFile,
            Set<String> expectedBackendNames
    ) {
        Path nonNullSecretsFile = Objects.requireNonNull(
                secretsFile,
                "secretsFile cannot be null"
        );
        Set<String> expected = Set.copyOf(
                Objects.requireNonNull(
                        expectedBackendNames,
                        "expectedBackendNames cannot be null"
                )
        );

        if (expected.isEmpty()) {
            throw new IllegalArgumentException(
                    "expectedBackendNames cannot be empty"
            );
        }

        if (!Files.isRegularFile(nonNullSecretsFile)) {
            throw new IllegalStateException(
                    "Backend control secrets file does not exist: "
                            + nonNullSecretsFile
            );
        }

        Map<String, byte[]> loaded = new LinkedHashMap<>();

        final java.util.List<String> lines;
        try {
            lines = Files.readAllLines(
                    nonNullSecretsFile,
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load backend control secrets",
                    exception
            );
        }

        try {
            for (int index = 0; index < lines.size(); index++) {
                String rawLine = lines.get(index);
                String line = rawLine.trim();

                if (line.isEmpty()
                        || line.startsWith("#")
                        || line.startsWith("!")) {
                    continue;
                }

                int separator = line.indexOf('=');
                if (separator <= 0 || separator == line.length() - 1) {
                    throw invalidLine(index + 1);
                }

                String backendName = line.substring(0, separator).trim();
                String encodedSecret = line.substring(separator + 1).trim();

                if (!BACKEND_NAME_PATTERN.matcher(backendName).matches()) {
                    throw new IllegalStateException(
                            "Invalid backend name in control secrets at line "
                                    + (index + 1)
                    );
                }

                if (!expected.contains(backendName)) {
                    throw new IllegalStateException(
                            "Control secret configured for unauthorized backend: "
                                    + backendName
                    );
                }

                if (!BASE64_URL_PATTERN.matcher(encodedSecret).matches()) {
                    throw new IllegalStateException(
                            "Invalid Base64URL control secret for backend: "
                                    + backendName
                    );
                }

                final byte[] decoded;
                try {
                    decoded = Base64.getUrlDecoder().decode(encodedSecret);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalStateException(
                            "Invalid Base64URL control secret for backend: "
                                    + backendName,
                            exception
                    );
                }

                if (decoded.length < MIN_SECRET_BYTES
                        || decoded.length > MAX_SECRET_BYTES) {
                    Arrays.fill(decoded, (byte) 0);
                    throw new IllegalStateException(
                            "Control secret for backend "
                                    + backendName
                                    + " must contain between "
                                    + MIN_SECRET_BYTES
                                    + " and "
                                    + MAX_SECRET_BYTES
                                    + " decoded bytes"
                    );
                }

                byte[] previous = loaded.putIfAbsent(
                        backendName,
                        decoded
                );

                if (previous != null) {
                    Arrays.fill(decoded, (byte) 0);
                    throw new IllegalStateException(
                            "Duplicate backend control secret: "
                                    + backendName
                    );
                }
            }

            Set<String> missing = expected.stream()
                    .filter(name -> !loaded.containsKey(name))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "Missing backend control secrets for: "
                                + String.join(", ", missing)
                );
            }

            return new FileBackendControlSecretProvider(loaded);
        } catch (RuntimeException exception) {
            loaded.values().forEach(
                    secret -> Arrays.fill(secret, (byte) 0)
            );
            throw exception;
        }
    }

    @Override
    public Optional<byte[]> findSecret(String backendName) {
        String normalized = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();

        byte[] secret = secrets.get(normalized);
        if (secret == null) {
            return Optional.empty();
        }
        return Optional.of(secret.clone());
    }

    @Override
    public void close() {
        secrets.values().forEach(
                secret -> Arrays.fill(secret, (byte) 0)
        );
        secrets.clear();
    }

    public int size() {
        return secrets.size();
    }

    private static IllegalStateException invalidLine(int lineNumber) {
        return new IllegalStateException(
                "Invalid backend control secrets entry at line "
                        + lineNumber
                        + "; expected backend-name=BASE64URL_SECRET"
        );
    }
}
