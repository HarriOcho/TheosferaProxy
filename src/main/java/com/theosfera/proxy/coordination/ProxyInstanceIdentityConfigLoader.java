package com.theosfera.proxy.coordination;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

public final class ProxyInstanceIdentityConfigLoader {

    public static final String FILE_NAME =
            "proxy-instance.properties";

    private static final String PROXY_NAME_PROPERTY =
            "proxy-name";

    private static final int MAX_PROXY_NAME_LENGTH = 32;

    private static final Pattern SAFE_PROXY_NAME =
            Pattern.compile(
                    "[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?"
            );

    private static final String DEFAULT_CONFIG = """
            # Identidad estable de esta instancia logica de Proxy.
            # Cambia proxy-name para cada instancia Velocity distinta.
            # Formato permitido: letras minusculas, numeros y guiones; 1-32 caracteres.
            proxy-name=proxy-1
            """;

    private final Path configFile;

    public ProxyInstanceIdentityConfigLoader(
            Path dataDirectory
    ) {
        Objects.requireNonNull(
                dataDirectory,
                "dataDirectory cannot be null"
        );

        this.configFile = dataDirectory.resolve(FILE_NAME);
    }

    public String loadProxyName() {
        createDefaultConfigIfMissing();

        Properties properties = new Properties();

        try (Reader reader = Files.newBufferedReader(
                configFile,
                StandardCharsets.UTF_8
        )) {
            properties.load(reader);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load proxy instance identity configuration",
                    exception
            );
        }

        String rawProxyName = properties.getProperty(
                PROXY_NAME_PROPERTY
        );

        if (rawProxyName == null) {
            throw new IllegalStateException(
                    "Proxy instance identity configuration requires "
                            + PROXY_NAME_PROPERTY
            );
        }

        String proxyName = rawProxyName.trim();

        if (proxyName.isEmpty()) {
            throw new IllegalStateException(
                    "Proxy instance identity "
                            + PROXY_NAME_PROPERTY
                            + " cannot be blank"
            );
        }

        if (proxyName.length() > MAX_PROXY_NAME_LENGTH) {
            throw new IllegalStateException(
                    "Proxy instance identity "
                            + PROXY_NAME_PROPERTY
                            + " is too long: maximum "
                            + MAX_PROXY_NAME_LENGTH
                            + " characters"
            );
        }

        if (!SAFE_PROXY_NAME.matcher(proxyName).matches()) {
            throw new IllegalStateException(
                    "Proxy instance identity "
                            + PROXY_NAME_PROPERTY
                            + " has invalid format: use lowercase "
                            + "letters, numbers and hyphens"
            );
        }

        return proxyName;
    }

    public Path configFile() {
        return configFile;
    }

    private void createDefaultConfigIfMissing() {
        if (Files.exists(configFile)) {
            return;
        }

        try {
            Files.createDirectories(
                    configFile.getParent()
            );
            Files.writeString(
                    configFile,
                    DEFAULT_CONFIG,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not create default proxy instance "
                            + "identity configuration",
                    exception
            );
        }
    }
}
