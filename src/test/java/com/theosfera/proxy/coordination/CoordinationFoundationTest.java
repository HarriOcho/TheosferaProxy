package com.theosfera.proxy.coordination;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoordinationFoundationTest {

    @Test
    void exposesDefinedOperationalStates() {
        assertArrayEquals(
                new CoordinationState[]{
                        CoordinationState.STARTING,
                        CoordinationState.HEALTHY,
                        CoordinationState.DEGRADED,
                        CoordinationState.FENCED,
                        CoordinationState.STOPPING
                },
                CoordinationState.values()
        );
    }

    @Test
    void normalizesProxyNameAndPreservesIncarnation() {
        UUID incarnationId = UUID.randomUUID();

        ProxyInstanceIdentity identity =
                new ProxyInstanceIdentity(
                        "  proxy-1  ",
                        incarnationId
                );

        assertEquals("proxy-1", identity.proxyName());
        assertEquals(incarnationId, identity.incarnationId());
    }

    @Test
    void rejectsInvalidProxyIdentity() {
        assertThrows(
                NullPointerException.class,
                () -> new ProxyInstanceIdentity(
                        null,
                        UUID.randomUUID()
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProxyInstanceIdentity(
                        "   ",
                        UUID.randomUUID()
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProxyInstanceIdentity(
                        "Proxy-1",
                        UUID.randomUUID()
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProxyInstanceIdentity(
                        "proxy/1",
                        UUID.randomUUID()
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProxyInstanceIdentity(
                        "a".repeat(33),
                        UUID.randomUUID()
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new ProxyInstanceIdentity(
                        "proxy-1",
                        null
                )
        );
    }

    @Test
    void stableProxyNameCanHaveDistinctIncarnations() {
        ProxyInstanceIdentity first =
                new ProxyInstanceIdentity(
                        "proxy-1",
                        UUID.randomUUID()
                );

        ProxyInstanceIdentity second =
                new ProxyInstanceIdentity(
                        "proxy-1",
                        UUID.randomUUID()
                );

        assertEquals(
                first.proxyName(),
                second.proxyName()
        );
        assertNotEquals(
                first.incarnationId(),
                second.incarnationId()
        );
    }

    @Test
    void configuredProxyNamesDifferentiateLogicalInstances() {
        ProxyInstanceIdentity first =
                new ProxyInstanceIdentity(
                        "proxy-1",
                        UUID.randomUUID()
                );

        ProxyInstanceIdentity second =
                new ProxyInstanceIdentity(
                        "proxy-2",
                        UUID.randomUUID()
                );

        assertNotEquals(
                first.proxyName(),
                second.proxyName()
        );
    }
}
