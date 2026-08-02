package com.theosfera.proxy.coordination.distributed.redis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowJarRedisPackagingTest {

    private static final Path SHADOW_JAR = Path.of(
            "build",
            "libs",
            "TheosferaProxy-0.1.0-SNAPSHOT.jar"
    );

    @Test
    void redisRuntimeDependenciesAreRelocated()
            throws Exception {
        try (ZipFile jar = new ZipFile(SHADOW_JAR.toFile())) {
            List<String> entries = jar.stream()
                    .map(entry -> entry.getName())
                    .toList();

            assertFalse(hasEntryStartingWith(entries, "io/lettuce/"));
            assertFalse(hasEntryStartingWith(entries, "io/netty/"));
            assertFalse(hasEntryStartingWith(entries, "reactor/"));
            assertFalse(hasEntryStartingWith(entries, "org/reactivestreams/"));
            assertFalse(hasEntryStartingWith(
                    entries,
                    "redis/clients/authentication/"
            ));

            assertTrue(hasEntryStartingWith(
                    entries,
                    "com/theosfera/proxy/libs/lettuce/"
            ));
            assertTrue(hasEntryStartingWith(
                    entries,
                    "com/theosfera/proxy/libs/netty/"
            ));
            assertTrue(hasEntryStartingWith(
                    entries,
                    "com/theosfera/proxy/libs/reactor/"
            ));
            assertTrue(hasEntryStartingWith(
                    entries,
                    "com/theosfera/proxy/libs/reactivestreams/"
            ));
            assertTrue(hasEntryStartingWith(
                    entries,
                    "com/theosfera/proxy/libs/redisauth/"
            ));
        }
    }

    @Test
    void serviceDescriptorsDoNotReferenceOriginalRedisPackages()
            throws Exception {
        try (ZipFile jar = new ZipFile(SHADOW_JAR.toFile())) {
            List<String> serviceDescriptors = jar.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.startsWith(
                            "META-INF/services/"
                    ))
                    .toList();

            assertFalse(serviceDescriptors.isEmpty());

            for (String descriptor : serviceDescriptors) {
                String contents = new String(
                        jar.getInputStream(
                                jar.getEntry(descriptor)
                        ).readAllBytes(),
                        StandardCharsets.UTF_8
                );

                for (String line : contents.split("\\R")) {
                    String provider = line.trim();

                    if (provider.isEmpty()
                            || provider.startsWith("#")) {
                        continue;
                    }

                    assertFalse(provider.startsWith("io.lettuce."));
                    assertFalse(provider.startsWith("io.netty."));
                    assertFalse(provider.startsWith("reactor."));
                    assertFalse(provider.startsWith(
                            "org.reactivestreams."
                    ));
                    assertFalse(provider.startsWith(
                            "redis.clients.authentication."
                    ));
                }
            }
        }
    }

    @Test
    void shadowJarDoesNotContainNativeRuntimeBinaries()
            throws Exception {
        try (ZipFile jar = new ZipFile(SHADOW_JAR.toFile())) {
            List<String> nativeBinaries = jar.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.endsWith(".so")
                            || name.endsWith(".dll")
                            || name.endsWith(".dylib"))
                    .toList();

            assertTrue(
                    nativeBinaries.isEmpty(),
                    () -> "Unexpected native binaries: "
                            + nativeBinaries
            );
        }
    }

    private boolean hasEntryStartingWith(
            List<String> entries,
            String prefix
    ) {
        return entries.stream().anyMatch(
                entry -> entry.startsWith(prefix)
        );
    }
}
