package com.theosfera.proxy.control;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

public final class ControlTlsContextFactory {

    private static final String KEY_STORE_TYPE = "PKCS12";
    private static final String TLS_PROTOCOL = "TLS";

    public SSLContext createServerContext(
            Path keyStorePath,
            char[] keyStorePassword
    ) {
        Path nonNullKeyStorePath = Objects.requireNonNull(
                keyStorePath,
                "keyStorePath cannot be null"
        );
        char[] password = Objects.requireNonNull(
                keyStorePassword,
                "keyStorePassword cannot be null"
        ).clone();

        if (password.length == 0) {
            throw new IllegalArgumentException(
                    "keyStorePassword cannot be empty"
            );
        }

        try {
            if (!Files.isRegularFile(nonNullKeyStorePath)) {
                throw new IllegalStateException(
                        "Control TLS PKCS12 keystore does not exist: "
                                + nonNullKeyStorePath
                );
            }

            KeyStore keyStore = KeyStore.getInstance(KEY_STORE_TYPE);
            try (InputStream input = Files.newInputStream(
                    nonNullKeyStorePath
            )) {
                keyStore.load(input, password);
            }

            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(
                            KeyManagerFactory.getDefaultAlgorithm()
                    );
            keyManagerFactory.init(keyStore, password);

            SSLContext sslContext = SSLContext.getInstance(TLS_PROTOCOL);
            sslContext.init(
                    keyManagerFactory.getKeyManagers(),
                    null,
                    new SecureRandom()
            );
            return sslContext;
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException(
                    "Could not initialize backend control TLS context",
                    exception
            );
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
