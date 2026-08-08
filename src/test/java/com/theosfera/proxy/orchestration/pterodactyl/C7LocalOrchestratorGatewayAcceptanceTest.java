package com.theosfera.proxy.orchestration.pterodactyl;

import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
import com.theosfera.proxy.orchestration.BackendOrchestrationProvider;
import com.theosfera.proxy.orchestration.BackendStartRequest;
import com.theosfera.proxy.orchestration.BackendStartResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(
        named = "theosfera.c7l.proxy.gateway",
        matches = "true"
)
class C7LocalOrchestratorGatewayAcceptanceTest {

    private static final String BACKEND_NAME = "lobby-2";
    private static final String PTERODACTYL_TARGET = "ptero-lobby-2";
    private static final String GATEWAY_TOKEN_ENV =
            "THEOSFERA_C7L_GATEWAY_TOKEN";

    private static final UUID PLAYER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000003"
    );
    private static final UUID PROXY_INCARNATION_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001"
    );
    private static final UUID REQUEST_40 = UUID.fromString(
            "00000000-0000-0000-0000-000000000040"
    );
    private static final UUID REQUEST_41 = UUID.fromString(
            "00000000-0000-0000-0000-000000000041"
    );
    private static final UUID REQUEST_CONFLICT = UUID.fromString(
            "00000000-0000-0000-0000-000000000099"
    );

    @Test
    void realGatewayProviderTraversesOrchestratorAndMapsFencingSemantics()
            throws Exception {
        Path trustStorePath = Path.of(requiredEnvironment(
                "THEOSFERA_C7L_GATEWAY_TRUSTSTORE"
        )).toAbsolutePath().normalize();
        char[] trustStorePassword = requiredEnvironment(
                "THEOSFERA_C7L_GATEWAY_TRUSTSTORE_PASSWORD"
        ).toCharArray();

        SSLContext previousDefault = SSLContext.getDefault();
        try {
            SSLContext.setDefault(clientTrustContext(
                    trustStorePath,
                    trustStorePassword
            ));

            PterodactylGatewayConfig config = new PterodactylGatewayConfig(
                    true,
                    URI.create(requiredEnvironment(
                            "THEOSFERA_C7L_GATEWAY_URI"
                    )),
                    Duration.ofSeconds(5),
                    GATEWAY_TOKEN_ENV,
                    Map.of(BACKEND_NAME, PTERODACTYL_TARGET)
            );

            BackendOrchestrationProvider provider =
                    PterodactylGatewayProviderFactory.create(config)
                            .orElseThrow();

            assertStatus(
                    provider,
                    request(41L, REQUEST_41),
                    BackendStartResult.Status.ACCEPTED
            );
            assertStatus(
                    provider,
                    request(41L, REQUEST_41),
                    BackendStartResult.Status.ACCEPTED
            );
            assertStatus(
                    provider,
                    request(40L, REQUEST_40),
                    BackendStartResult.Status.STALE_AUTHORITY
            );
            assertStatus(
                    provider,
                    request(41L, REQUEST_CONFLICT),
                    BackendStartResult.Status.CONFLICT
            );
        } finally {
            SSLContext.setDefault(previousDefault);
            Arrays.fill(trustStorePassword, '\0');
        }
    }

    private static void assertStatus(
            BackendOrchestrationProvider provider,
            BackendStartRequest request,
            BackendStartResult.Status expected
    ) {
        BackendStartResult result = provider.requestStart(request)
                .toCompletableFuture()
                .join();
        assertEquals(expected, result.status());
    }

    private static BackendStartRequest request(long fence, UUID requestId) {
        ProxyMembershipLease membership = new ProxyMembershipLease(
                new ProxyInstanceIdentity(
                        "proxy-1",
                        PROXY_INCARNATION_ID
                ),
                7L
        );
        BackendBootstrapLease bootstrapLease = new BackendBootstrapLease(
                BACKEND_NAME,
                requestId,
                PLAYER_ID,
                membership,
                fence
        );
        return new BackendStartRequest(bootstrapLease);
    }

    private static SSLContext clientTrustContext(
            Path trustStorePath,
            char[] password
    ) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(trustStorePath)) {
            trustStore.load(input, password);
        }

        TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm()
                );
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(
                null,
                trustManagerFactory.getTrustManagers(),
                null
        );
        return sslContext;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Required C.7L environment variable is missing: " + name
            );
        }
        return value.trim();
    }
}
