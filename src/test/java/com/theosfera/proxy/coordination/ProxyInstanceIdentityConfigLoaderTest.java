package com.theosfera.proxy.coordination;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyInstanceIdentityConfigLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAndLoadsDefaultConfiguration()
            throws IOException {
        ProxyInstanceIdentityConfigLoader loader =
                new ProxyInstanceIdentityConfigLoader(
                        temporaryDirectory
                );

        String proxyName = loader.loadProxyName();

        assertEquals("proxy-1", proxyName);
        assertTrue(Files.isRegularFile(
                loader.configFile()
        ));

        String generatedConfiguration = Files.readString(
                loader.configFile(),
                StandardCharsets.UTF_8
        );

        assertTrue(generatedConfiguration.contains(
                "proxy-name=proxy-1"
        ));
    }

    @Test
    void loadsConfiguredProxyName()
            throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(
                        ProxyInstanceIdentityConfigLoader
                                .FILE_NAME
                ),
                "proxy-name=proxy-east-1",
                StandardCharsets.UTF_8
        );

        String proxyName =
                new ProxyInstanceIdentityConfigLoader(
                        temporaryDirectory
                ).loadProxyName();

        assertEquals("proxy-east-1", proxyName);
    }

    @Test
    void trimsConfiguredProxyName()
            throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(
                        ProxyInstanceIdentityConfigLoader
                                .FILE_NAME
                ),
                "proxy-name=  proxy-2  ",
                StandardCharsets.UTF_8
        );

        String proxyName =
                new ProxyInstanceIdentityConfigLoader(
                        temporaryDirectory
                ).loadProxyName();

        assertEquals("proxy-2", proxyName);
    }

    @Test
    void rejectsMissingProxyName()
            throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(
                        ProxyInstanceIdentityConfigLoader
                                .FILE_NAME
                ),
                "other=value",
                StandardCharsets.UTF_8
        );

        assertThrows(
                IllegalStateException.class,
                () -> new ProxyInstanceIdentityConfigLoader(
                        temporaryDirectory
                ).loadProxyName()
        );
    }

    @Test
    void rejectsBlankProxyName()
            throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(
                        ProxyInstanceIdentityConfigLoader
                                .FILE_NAME
                ),
                "proxy-name=   ",
                StandardCharsets.UTF_8
        );

        assertThrows(
                IllegalStateException.class,
                () -> new ProxyInstanceIdentityConfigLoader(
                        temporaryDirectory
                ).loadProxyName()
        );
    }

    @Test
    void rejectsInvalidProxyNameFormat()
            throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(
                        ProxyInstanceIdentityConfigLoader
                                .FILE_NAME
                ),
                "proxy-name=proxy/1",
                StandardCharsets.UTF_8
        );

        assertThrows(
                IllegalStateException.class,
                () -> new ProxyInstanceIdentityConfigLoader(
                        temporaryDirectory
                ).loadProxyName()
        );
    }

    @Test
    void rejectsExcessivelyLongProxyName()
            throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(
                        ProxyInstanceIdentityConfigLoader
                                .FILE_NAME
                ),
                "proxy-name=" + "a".repeat(33),
                StandardCharsets.UTF_8
        );

        assertThrows(
                IllegalStateException.class,
                () -> new ProxyInstanceIdentityConfigLoader(
                        temporaryDirectory
                ).loadProxyName()
        );
    }

    @Test
    void createsStableIdentityWithRuntimeIncarnation()
            throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(
                        ProxyInstanceIdentityConfigLoader
                                .FILE_NAME
                ),
                """
                proxy-name=proxy-stable
                incarnation-id=00000000-0000-0000-0000-000000000000
                """,
                StandardCharsets.UTF_8
        );

        ProxyInstanceIdentityConfigLoader loader =
                new ProxyInstanceIdentityConfigLoader(
                        temporaryDirectory
                );

        ProxyInstanceIdentity first =
                new ProxyInstanceIdentity(
                        loader.loadProxyName(),
                        UUID.randomUUID()
                );

        ProxyInstanceIdentity second =
                new ProxyInstanceIdentity(
                        loader.loadProxyName(),
                        UUID.randomUUID()
                );

        assertEquals("proxy-stable", first.proxyName());
        assertEquals("proxy-stable", second.proxyName());
        assertNotEquals(
                first.incarnationId(),
                second.incarnationId()
        );
        assertNotEquals(
                "00000000-0000-0000-0000-000000000000",
                first.incarnationId().toString()
        );
    }

    @Test
    void configuredNamesDifferentiateInstances()
            throws IOException {
        Path firstDirectory = temporaryDirectory.resolve(
                "first"
        );
        Path secondDirectory = temporaryDirectory.resolve(
                "second"
        );

        Files.createDirectories(firstDirectory);
        Files.createDirectories(secondDirectory);

        Files.writeString(
                firstDirectory.resolve(
                        ProxyInstanceIdentityConfigLoader
                                .FILE_NAME
                ),
                "proxy-name=proxy-1",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                secondDirectory.resolve(
                        ProxyInstanceIdentityConfigLoader
                                .FILE_NAME
                ),
                "proxy-name=proxy-2",
                StandardCharsets.UTF_8
        );

        ProxyInstanceIdentity first =
                new ProxyInstanceIdentity(
                        new ProxyInstanceIdentityConfigLoader(
                                firstDirectory
                        ).loadProxyName(),
                        UUID.randomUUID()
                );
        ProxyInstanceIdentity second =
                new ProxyInstanceIdentity(
                        new ProxyInstanceIdentityConfigLoader(
                                secondDirectory
                        ).loadProxyName(),
                        UUID.randomUUID()
                );

        assertNotEquals(
                first.proxyName(),
                second.proxyName()
        );
    }

    @Test
    void rejectsNullDataDirectory() {
        assertThrows(
                NullPointerException.class,
                () -> new ProxyInstanceIdentityConfigLoader(
                        null
                )
        );
    }
}
