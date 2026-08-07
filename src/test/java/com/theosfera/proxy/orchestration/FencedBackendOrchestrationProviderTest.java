package com.theosfera.proxy.orchestration;

import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FencedBackendOrchestrationProviderTest {

    @Test
    void resolvesTrustedTargetAndPassesExactBootstrapAuthority() {
        BackendBootstrapLease lease = bootstrapLease(41L);
        BackendStartTarget target =
                new BackendStartTarget(
                        "lobby-2",
                        "orchestrator-target-17"
                );
        BackendStartTargetResolver resolver = mock(
                BackendStartTargetResolver.class
        );
        BackendStartActuator actuator = mock(BackendStartActuator.class);

        when(resolver.resolve("lobby-2"))
                .thenReturn(Optional.of(target));
        when(actuator.startIfCurrent(any()))
                .thenAnswer(invocation -> {
                    BackendStartActuationRequest request =
                            invocation.getArgument(0);
                    assertSame(target, request.target());
                    assertSame(lease, request.bootstrapLease());
                    return CompletableFuture.completedFuture(
                            BackendStartActuationResult.accepted()
                    );
                });

        BackendStartResult result =
                new FencedBackendOrchestrationProvider(
                        resolver,
                        actuator
                ).requestStart(new BackendStartRequest(lease))
                        .toCompletableFuture()
                        .join();

        assertEquals(
                BackendStartResult.Status.ACCEPTED,
                result.status()
        );
    }

    @Test
    void missingTrustedTargetFailsWithoutCallingActuator() {
        BackendStartTargetResolver resolver = mock(
                BackendStartTargetResolver.class
        );
        BackendStartActuator actuator = mock(BackendStartActuator.class);

        when(resolver.resolve("lobby-2"))
                .thenReturn(Optional.empty());

        BackendStartResult result =
                new FencedBackendOrchestrationProvider(
                        resolver,
                        actuator
                ).requestStart(
                        new BackendStartRequest(bootstrapLease(41L))
                ).toCompletableFuture().join();

        assertEquals(
                BackendStartResult.Status.TARGET_NOT_FOUND,
                result.status()
        );
        verify(actuator, never()).startIfCurrent(any());
    }

    @Test
    void mismatchedTrustedTargetFailsBeforeSideEffect() {
        BackendStartTargetResolver resolver = mock(
                BackendStartTargetResolver.class
        );
        BackendStartActuator actuator = mock(BackendStartActuator.class);

        when(resolver.resolve("lobby-2"))
                .thenReturn(Optional.of(
                        new BackendStartTarget(
                                "skyblock-1",
                                "orchestrator-target-17"
                        )
                ));

        assertThrows(
                IllegalArgumentException.class,
                () -> new FencedBackendOrchestrationProvider(
                        resolver,
                        actuator
                ).requestStart(
                        new BackendStartRequest(bootstrapLease(41L))
                )
        );
        verify(actuator, never()).startIfCurrent(any());
    }

    @Test
    void mapsActuatorStatusesWithoutTurningFailuresIntoAccepted() {
        assertMapped(
                BackendStartActuationResult.Status.STALE_AUTHORITY,
                BackendStartResult.Status.STALE_AUTHORITY
        );
        assertMapped(
                BackendStartActuationResult.Status.CONFLICT,
                BackendStartResult.Status.CONFLICT
        );
        assertMapped(
                BackendStartActuationResult.Status.ACTUATOR_UNAVAILABLE,
                BackendStartResult.Status.PROVIDER_UNAVAILABLE
        );
        assertMapped(
                BackendStartActuationResult.Status.REJECTED,
                BackendStartResult.Status.REJECTED
        );
    }

    @Test
    void exactReplayCarriesTheSameImmutableAuthorityAgain() {
        BackendBootstrapLease lease = bootstrapLease(41L);
        BackendStartTarget target =
                new BackendStartTarget(
                        "lobby-2",
                        "orchestrator-target-17"
                );
        BackendStartTargetResolver resolver = backendName ->
                Optional.of(target);
        BackendStartActuationRequest[] observed =
                new BackendStartActuationRequest[2];
        int[] invocation = {0};
        BackendStartActuator actuator = request -> {
            observed[invocation[0]++] = request;
            return CompletableFuture.completedFuture(
                    BackendStartActuationResult.accepted()
            );
        };
        FencedBackendOrchestrationProvider provider =
                new FencedBackendOrchestrationProvider(
                        resolver,
                        actuator
                );
        BackendStartRequest request = new BackendStartRequest(lease);

        provider.requestStart(request).toCompletableFuture().join();
        provider.requestStart(request).toCompletableFuture().join();

        assertSame(lease, observed[0].bootstrapLease());
        assertSame(lease, observed[1].bootstrapLease());
        assertSame(target, observed[0].target());
        assertSame(target, observed[1].target());
    }

    @Test
    void atomicActuatorContractRejectsStaleAndConflictingAuthority() {
        BackendStartTarget target =
                new BackendStartTarget(
                        "lobby-2",
                        "orchestrator-target-17"
                );
        AtomicTestActuator actuator = new AtomicTestActuator();
        FencedBackendOrchestrationProvider provider =
                new FencedBackendOrchestrationProvider(
                        backendName -> Optional.of(target),
                        actuator
                );

        BackendBootstrapLease lease41 = bootstrapLease(
                41L,
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000002"
                )
        );
        BackendBootstrapLease stale40 = bootstrapLease(
                40L,
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000004"
                )
        );
        BackendBootstrapLease conflicting41 = bootstrapLease(
                41L,
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000005"
                )
        );
        BackendBootstrapLease lease42 = bootstrapLease(
                42L,
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000006"
                )
        );

        assertEquals(
                BackendStartResult.Status.ACCEPTED,
                start(provider, lease41).status()
        );
        assertEquals(
                BackendStartResult.Status.ACCEPTED,
                start(provider, lease41).status()
        );
        assertEquals(
                BackendStartResult.Status.STALE_AUTHORITY,
                start(provider, stale40).status()
        );
        assertEquals(
                BackendStartResult.Status.CONFLICT,
                start(provider, conflicting41).status()
        );
        assertEquals(
                BackendStartResult.Status.ACCEPTED,
                start(provider, lease42).status()
        );

        assertEquals(2, actuator.sideEffectCount());
    }

    @Test
    void targetRejectsBlankOrControlCharacterReferences() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendStartTarget("lobby-2", "   ")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendStartTarget(
                        "lobby-2",
                        "target\nnext-command"
                )
        );
    }

    private static BackendStartResult start(
            FencedBackendOrchestrationProvider provider,
            BackendBootstrapLease lease
    ) {
        return provider.requestStart(new BackendStartRequest(lease))
                .toCompletableFuture()
                .join();
    }

    private static void assertMapped(
            BackendStartActuationResult.Status actuationStatus,
            BackendStartResult.Status expectedStatus
    ) {
        BackendStartTargetResolver resolver = backendName ->
                Optional.of(new BackendStartTarget(
                        backendName,
                        "orchestrator-target-17"
                ));
        BackendStartActuator actuator = request ->
                CompletableFuture.completedFuture(
                        BackendStartActuationResult.of(actuationStatus)
                );

        BackendStartResult result =
                new FencedBackendOrchestrationProvider(
                        resolver,
                        actuator
                ).requestStart(
                        new BackendStartRequest(bootstrapLease(41L))
                ).toCompletableFuture().join();

        assertEquals(expectedStatus, result.status());
    }

    private static BackendBootstrapLease bootstrapLease(
            long bootstrapFencingToken
    ) {
        return bootstrapLease(
                bootstrapFencingToken,
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000002"
                )
        );
    }

    private static BackendBootstrapLease bootstrapLease(
            long bootstrapFencingToken,
            UUID requestId
    ) {
        ProxyMembershipLease membershipLease =
                new ProxyMembershipLease(
                        new ProxyInstanceIdentity(
                                "proxy-1",
                                UUID.fromString(
                                        "00000000-0000-0000-0000-000000000001"
                                )
                        ),
                        7L
                );

        return new BackendBootstrapLease(
                "lobby-2",
                requestId,
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000003"
                ),
                membershipLease,
                bootstrapFencingToken
        );
    }

    private static final class AtomicTestActuator
            implements BackendStartActuator {

        private final Map<String, AcceptedOperation> acceptedByBackend =
                new HashMap<>();
        private int sideEffectCount;

        @Override
        public synchronized CompletionStage<BackendStartActuationResult>
        startIfCurrent(BackendStartActuationRequest request) {
            String backendName = request.target().backendName();
            AcceptedOperation incoming = AcceptedOperation.from(request);
            AcceptedOperation current = acceptedByBackend.get(backendName);

            if (current == null
                    || incoming.bootstrapFencingToken()
                    > current.bootstrapFencingToken()) {
                acceptedByBackend.put(backendName, incoming);
                sideEffectCount++;
                return completed(
                        BackendStartActuationResult.Status.ACCEPTED
                );
            }

            if (incoming.bootstrapFencingToken()
                    < current.bootstrapFencingToken()) {
                return completed(
                        BackendStartActuationResult.Status.STALE_AUTHORITY
                );
            }

            if (incoming.equals(current)) {
                return completed(
                        BackendStartActuationResult.Status.ACCEPTED
                );
            }

            return completed(
                    BackendStartActuationResult.Status.CONFLICT
            );
        }

        int sideEffectCount() {
            return sideEffectCount;
        }

        private static CompletionStage<BackendStartActuationResult> completed(
                BackendStartActuationResult.Status status
        ) {
            return CompletableFuture.completedFuture(
                    BackendStartActuationResult.of(status)
            );
        }
    }

    private record AcceptedOperation(
            String targetReference,
            UUID requestId,
            UUID playerId,
            ProxyMembershipLease ownerMembership,
            long bootstrapFencingToken
    ) {

        private static AcceptedOperation from(
                BackendStartActuationRequest request
        ) {
            BackendBootstrapLease lease = request.bootstrapLease();
            return new AcceptedOperation(
                    request.target().targetReference(),
                    lease.requestId(),
                    lease.playerId(),
                    lease.ownerMembership(),
                    lease.fencingToken()
            );
        }
    }
}
