package com.theosfera.proxy.coordination.local;

import com.theosfera.proxy.coordination.PlayerSessionAcquireResult;
import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionLeaseRequest;
import com.theosfera.proxy.coordination.PlayerSessionRenewResult;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerSessionRegistrationResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class LocalPlayerSessionCoordinator
        implements PlayerSessionCoordinator {

    private final AuthenticatedPlayerSessionRegistry sessionRegistry;
    private final Map<UUID, PlayerSessionLease> leases =
            new HashMap<>();

    private long lastFencingToken;

    public LocalPlayerSessionCoordinator(
            AuthenticatedPlayerSessionRegistry sessionRegistry
    ) {
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );
    }

    @Override
    public CompletionStage<PlayerSessionAcquireResult> acquire(
            PlayerSessionLeaseRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request cannot be null"
        );

        return CompletableFuture.completedFuture(
                acquireSynchronously(request)
        );
    }

    @Override
    public CompletionStage<PlayerSessionRenewResult> renew(
            PlayerSessionLease expected
    ) {
        Objects.requireNonNull(
                expected,
                "expected cannot be null"
        );

        return CompletableFuture.completedFuture(
                renewSynchronously(expected)
        );
    }

    @Override
    public CompletionStage<Boolean> releaseIfOwned(
            PlayerSessionLease expected
    ) {
        Objects.requireNonNull(
                expected,
                "expected cannot be null"
        );

        return CompletableFuture.completedFuture(
                releaseSynchronously(expected)
        );
    }

    private synchronized PlayerSessionAcquireResult
            acquireSynchronously(
                    PlayerSessionLeaseRequest request
            ) {
        UUID playerId = request.session().playerId();

        PlayerSessionLease existing = leases.get(playerId);

        if (existing != null) {
            if (!existing.owner().equals(request.owner())) {
                return PlayerSessionAcquireResult.withoutLease(
                        PlayerSessionAcquireResult.Status
                                .OWNED_BY_OTHER_PROXY
                );
            }

            if (!existing.session().equals(request.session())) {
                return PlayerSessionAcquireResult.withoutLease(
                        PlayerSessionAcquireResult.Status.CONFLICT
                );
            }

            return PlayerSessionAcquireResult.alreadyOwned(
                    existing
            );
        }

        PlayerSessionRegistrationResult registrationResult =
                sessionRegistry.register(request.session());

        if (registrationResult
                == PlayerSessionRegistrationResult.CONFLICT) {
            return PlayerSessionAcquireResult.withoutLease(
                    PlayerSessionAcquireResult.Status.CONFLICT
            );
        }

        PlayerSessionLease lease =
                new PlayerSessionLease(
                        request.session(),
                        request.owner(),
                        nextFencingToken()
                );

        leases.put(playerId, lease);

        return PlayerSessionAcquireResult.acquired(lease);
    }

    private synchronized PlayerSessionRenewResult
            renewSynchronously(
                    PlayerSessionLease expected
            ) {
        PlayerSessionLease existing =
                leases.get(expected.session().playerId());

        if (existing == null) {
            return PlayerSessionRenewResult.withoutLease(
                    PlayerSessionRenewResult.Status.NOT_FOUND
            );
        }

        if (!existing.owner().equals(expected.owner())) {
            return PlayerSessionRenewResult.withoutLease(
                    PlayerSessionRenewResult.Status.NOT_OWNER
            );
        }

        if (!existing.equals(expected)) {
            return PlayerSessionRenewResult.withoutLease(
                    PlayerSessionRenewResult.Status.CONFLICT
            );
        }

        AuthenticatedPlayerSession registered =
                sessionRegistry.find(
                        expected.session().playerId()
                ).orElse(null);

        if (!expected.session().equals(registered)) {
            return PlayerSessionRenewResult.withoutLease(
                    PlayerSessionRenewResult.Status.CONFLICT
            );
        }

        return PlayerSessionRenewResult.renewed(existing);
    }

    private synchronized boolean releaseSynchronously(
            PlayerSessionLease expected
    ) {
        UUID playerId = expected.session().playerId();
        PlayerSessionLease existing = leases.get(playerId);

        if (!expected.equals(existing)) {
            return false;
        }

        boolean removedFromRegistry =
                sessionRegistry
                        .removeIfMatches(expected.session())
                        .isPresent();

        if (!removedFromRegistry) {
            return false;
        }

        return leases.remove(playerId, existing);
    }

    private long nextFencingToken() {
        lastFencingToken = Math.incrementExact(
                lastFencingToken
        );

        return lastFencingToken;
    }
}
