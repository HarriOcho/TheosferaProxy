package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.PlayerSessionReleaseTimeoutScheduler.ReleaseTimeoutPhase;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerSessionReleaseServiceTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "7f8f1ff1-d856-4db7-b5f4-43df02d26b60"
            );

    private static final ProxyInstanceIdentity OWNER =
            new ProxyInstanceIdentity(
                    "proxy-release-service-test",
                    UUID.fromString(
                            "3aa94c59-70bb-4a1a-a386-bf6d20f26887"
                    )
            );

    private final PlayerSessionLeaseBindingRegistry registry =
            new PlayerSessionLeaseBindingRegistry();
    private final PlayerSessionCoordinator coordinator =
            mock(PlayerSessionCoordinator.class);
    private final Logger logger =
            mock(Logger.class);

    @Test
    void distinctEqualCompletionStagesHaveDifferentTimeoutIdentities() {
        PlayerSessionLease lease =
                lease(2L);
        CompletableFuture<Boolean> firstCompletion =
                new EqualCompletionStage();
        CompletableFuture<Boolean> secondCompletion =
                new EqualCompletionStage();

        assertTrue(firstCompletion.equals(secondCompletion));
        assertTrue(secondCompletion.equals(firstCompletion));
        assertEquals(
                firstCompletion.hashCode(),
                secondCompletion.hashCode()
        );

        PlayerSessionReleaseTimeoutScheduler.ReleaseTimeoutKey firstKey =
                new PlayerSessionReleaseTimeoutScheduler
                        .ReleaseTimeoutKey(
                        ReleaseTimeoutPhase.OWNED_RELEASE_TIMEOUT,
                        lease.session().playerId(),
                        lease,
                        lease.fencingToken(),
                        firstCompletion
                );
        PlayerSessionReleaseTimeoutScheduler.ReleaseTimeoutKey secondKey =
                new PlayerSessionReleaseTimeoutScheduler
                        .ReleaseTimeoutKey(
                        ReleaseTimeoutPhase.OWNED_RELEASE_TIMEOUT,
                        lease.session().playerId(),
                        lease,
                        lease.fencingToken(),
                        secondCompletion
                );

        assertNotEquals(firstKey, secondKey);
        assertNotEquals(firstKey.hashCode(), secondKey.hashCode());
    }

    @Test
    void ownedTimeoutScheduleFailureRemovesPendingReleaseFailClosed() {
        ownedTimeoutScheduleFailureRemovesPendingReleaseFailClosed(
                ScheduleFailure.THROW
        );
        ownedTimeoutScheduleFailureRemovesPendingReleaseFailClosed(
                ScheduleFailure.NULL
        );
    }

    private void ownedTimeoutScheduleFailureRemovesPendingReleaseFailClosed(
            ScheduleFailure failure
    ) {
        PlayerSessionLeaseBindingRegistry targetRegistry =
                new PlayerSessionLeaseBindingRegistry();
        PlayerSessionLease lease =
                lease(2L);
        CompletableFuture<Boolean> externalCompletion =
                new CompletableFuture<>();
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        scheduler.blockNextSchedule();
        scheduler.failNextSchedule(failure);
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        targetRegistry,
                        scheduler,
                        logger
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(externalCompletion);

        AtomicReference<Boolean> releaseStarted =
                new AtomicReference<>();
        Thread releaseThread =
                new Thread(
                        () -> releaseStarted.set(
                                releaseService.releaseIfUnbound(
                                        lease,
                                        new PlayerSessionReleaseService
                                                .ReleaseCallbacks() {
                                                }
                                )
                        )
                );
        releaseThread.start();

        scheduler.awaitScheduleEntered();

        TestWaiter waiter =
                attachWaiter(targetRegistry, lease);

        scheduler.releaseBlockedSchedule();
        join(releaseThread);

        assertTrue(releaseStarted.get());
        assertEquals(0, scheduler.scheduledCount());
        assertEquals(0, targetRegistry.exactQuarantineCount());
        assertTrue(waiter.completion().toCompletableFuture().isDone());
        assertFalse(waiter.completion().toCompletableFuture().join());
        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                targetRegistry.beginTracked(
                        waiter.player(),
                        waiter.acquisitionId(),
                        lease.session()
                ).decision()
        );
        assertFalse(
                targetRegistry.attachReleaseCompletion(
                        lease,
                        externalCompletion
                )
        );
        assertFalse(
                targetRegistry.reserveReleaseIfUnbound(
                        lease
                )
        );
        targetRegistry.clear();

        externalCompletion.complete(true);

        assertEquals(0, targetRegistry.exactQuarantineCount());
    }

    @Test
    void ownedScheduleFailureAtFloorCapacityClosesUnknownPlayerAdmission() {
        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        System::nanoTime,
                        16,
                        60_000_000_000L,
                        60_000_000_000L,
                        8,
                        1
                );
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        scheduler.blockNextSchedule();
        scheduler.failNextSchedule(ScheduleFailure.THROW);
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        boundedRegistry,
                        scheduler,
                        logger
                );
        PlayerSessionLease firstLease =
                lease(PLAYER_ID, OWNER, 2L);
        CompletableFuture<Boolean> firstExternal =
                new CompletableFuture<>();
        PlayerSessionLease secondLease =
                lease(
                        UUID.fromString(
                                "f5bd77d6-764a-4d87-9f81-c16a47c44718"
                        ),
                        OWNER,
                        3L
                );
        CompletableFuture<Boolean> secondExternal =
                new CompletableFuture<>();

        assertTrue(
                boundedRegistry.reserveReleaseIfUnbound(firstLease)
        );
        assertTrue(
                boundedRegistry.attachReleaseCompletion(
                        firstLease,
                        firstExternal
                )
        );
        assertTrue(
                boundedRegistry.claimReleaseTimeout(
                        firstLease,
                        firstExternal
                )
        );
        assertTrue(
                boundedRegistry.claimReleaseQuarantineRetentionTimeout(
                        firstLease,
                        firstExternal
                )
        );

        when(coordinator.releaseIfOwned(secondLease))
                .thenReturn(secondExternal);

        AtomicReference<Boolean> releaseStarted =
                new AtomicReference<>();
        Thread releaseThread =
                new Thread(
                        () -> releaseStarted.set(
                                releaseService.releaseIfUnbound(
                                        secondLease,
                                        new PlayerSessionReleaseService
                                                .ReleaseCallbacks() {
                                                }
                                )
                        )
                );
        releaseThread.start();

        scheduler.awaitScheduleEntered();

        TestWaiter secondWaiter =
                attachWaiter(
                        boundedRegistry,
                        player(secondLease.session().playerId()),
                        UUID.fromString(
                                "434d4087-886b-4a55-b080-8614b3ec4d8c"
                        ),
                        secondLease
                );

        scheduler.releaseBlockedSchedule();
        join(releaseThread);

        assertTrue(releaseStarted.get());
        assertTrue(
                secondWaiter
                        .completion()
                        .toCompletableFuture()
                        .isDone()
        );
        assertFalse(
                secondWaiter
                        .completion()
                        .toCompletableFuture()
                        .join()
        );
        assertEquals(0, boundedRegistry.exactQuarantineCount());
        assertFalse(
                boundedRegistry.reserveReleaseIfUnbound(secondLease)
        );

        PlayerSessionLease thirdLease =
                lease(
                        UUID.fromString(
                                "2841e0e8-7f0c-4f13-8a44-6e3a9ed700d1"
                        ),
                        OWNER,
                        100L
                );

        assertFalse(
                boundedRegistry.reserveReleaseIfUnbound(thirdLease)
        );

        PlayerSessionLease elevatedFirstLease =
                lease(PLAYER_ID, OWNER, 4L);

        assertTrue(
                boundedRegistry.reserveReleaseIfUnbound(
                        elevatedFirstLease
                )
        );
    }

    @Test
    void claimedTimeoutCannotMutateRegistryAfterLifecycleClear() {
        PlayerSessionLease lease =
                lease(2L);
        ManualCompletionStage sharedCompletion =
                new ManualCompletionStage();
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        BlockingLifecycleProbe probe =
                new BlockingLifecycleProbe();
        probe.blockOwnedTimeoutClaim();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        registry,
                        scheduler,
                        logger,
                        probe
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(sharedCompletion, sharedCompletion);

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );

        Thread oldTimeout =
                new Thread(() -> scheduler.scheduled(0).fire());
        oldTimeout.start();
        probe.awaitOwnedTimeoutClaimed();

        Thread clearThread =
                new Thread(releaseService::clear);
        clearThread.start();

        assertEquals(0, registry.exactQuarantineCount());

        probe.releaseOwnedTimeoutClaim();
        join(oldTimeout);
        join(clearThread);
        registry.clear();

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );
        assertEquals(0, registry.exactQuarantineCount());

        assertEquals(0, registry.exactQuarantineCount());
    }

    @Test
    void externalCallbackCannotCrossLifecycleClearBarrier() {
        PlayerSessionLease lease =
                lease(2L);
        ManualCompletionStage sharedCompletion =
                new ManualCompletionStage();
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        BlockingLifecycleProbe probe =
                new BlockingLifecycleProbe();
        probe.blockExternalCallback();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        registry,
                        scheduler,
                        logger,
                        probe
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(sharedCompletion, sharedCompletion);

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );

        Thread oldCallback =
                new Thread(() -> sharedCompletion.trigger(0, true));
        oldCallback.start();
        probe.awaitExternalCallbackAccepted();

        Thread clearThread =
                new Thread(releaseService::clear);
        clearThread.start();

        assertTrue(
                registry.attachReleaseCompletion(
                        lease,
                        sharedCompletion
                )
        );

        probe.releaseExternalCallback();
        join(oldCallback);
        join(clearThread);
        registry.clear();

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );
        assertTrue(
                registry.attachReleaseCompletion(
                        lease,
                        sharedCompletion
                )
        );

        assertTrue(
                registry.attachReleaseCompletion(
                        lease,
                        sharedCompletion
                )
        );
    }

    @Test
    void claimedRetentionTimeoutCannotCrossClear() {
        PlayerSessionLease lease =
                lease(2L);
        CompletableFuture<Boolean> externalCompletion =
                new CompletableFuture<>();
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        BlockingLifecycleProbe probe =
                new BlockingLifecycleProbe();
        probe.blockRetentionTimeoutClaim();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        registry,
                        scheduler,
                        logger,
                        probe
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(externalCompletion);

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );
        scheduler.scheduled(0).fire();
        assertEquals(1, registry.exactQuarantineCount());

        Thread oldRetention =
                new Thread(() -> scheduler.scheduled(1).fire());
        oldRetention.start();
        probe.awaitRetentionTimeoutClaimed();

        Thread clearThread =
                new Thread(releaseService::clear);
        clearThread.start();

        assertEquals(1, registry.exactQuarantineCount());

        probe.releaseRetentionTimeoutClaim();
        join(oldRetention);
        join(clearThread);
        registry.clear();

        assertEquals(0, registry.exactQuarantineCount());
    }

    @Test
    void schedulerReturnsHandleAfterLifecycleClear() {
        PlayerSessionLease lease =
                lease(2L);
        CompletableFuture<Boolean> externalCompletion =
                new CompletableFuture<>();
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        scheduler.blockNextSchedule();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        registry,
                        scheduler,
                        logger
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(externalCompletion);

        Thread releaseThread =
                new Thread(
                        () -> releaseService.releaseIfUnbound(
                                lease,
                                new PlayerSessionReleaseService
                                        .ReleaseCallbacks() {
                                        }
                        )
                );
        releaseThread.start();

        scheduler.awaitScheduleEntered();

        AtomicBoolean clearReturned =
                new AtomicBoolean(false);
        Thread clearThread =
                new Thread(
                        () -> {
                            releaseService.clear();
                            registry.clear();
                            clearReturned.set(true);
                        }
                );
        clearThread.start();

        assertFalse(clearReturned.get());

        scheduler.releaseBlockedSchedule();
        join(releaseThread);
        join(clearThread);

        assertEquals(1, scheduler.scheduledCount());
        assertTrue(scheduler.scheduled(0).cancelled());
        assertTrue(clearReturned.get());

        scheduler.scheduled(0).fire();
        externalCompletion.complete(true);

        assertEquals(0, registry.exactQuarantineCount());
    }

    @Test
    void clearReturnsWhileReleaseStartupIsPausedAfterAttachment() {
        PlayerSessionLease lease =
                lease(2L);
        CompletableFuture<Boolean> sharedCompletion =
                new CompletableFuture<>();
        PlayerSessionLeaseBindingRegistry blockedRegistry =
                mock(PlayerSessionLeaseBindingRegistry.class);
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        blockedRegistry,
                        scheduler,
                        logger
                );
        CountDownLatch oldAttached =
                new CountDownLatch(1);
        CountDownLatch resumeOld =
                new CountDownLatch(1);
        AtomicReference<Boolean> blockFirstAttachment =
                new AtomicReference<>(true);
        AtomicReference<Boolean> oldReleaseStarted =
                new AtomicReference<>();
        AtomicReference<Boolean> clearReturned =
                new AtomicReference<>(false);

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(
                        sharedCompletion,
                        sharedCompletion
                );
        when(blockedRegistry.reserveReleaseIfUnbound(lease))
                .thenReturn(true);

        doAnswer(invocation -> {
            boolean attached = true;

            if (attached
                    && blockFirstAttachment
                    .getAndSet(false)) {
                oldAttached.countDown();
                await(resumeOld);
            }

            return attached;
        }).when(blockedRegistry)
                .attachReleaseCompletion(
                        any(PlayerSessionLease.class),
                        any(CompletableFuture.class)
                );

        Thread oldRelease =
                new Thread(
                        () -> oldReleaseStarted.set(
                                releaseService.releaseIfUnbound(
                                        lease,
                                        new PlayerSessionReleaseService
                                                .ReleaseCallbacks() {
                                                }
                                )
                        )
                );
        oldRelease.start();

        await(oldAttached);

        Thread clearThread =
                new Thread(
                        () -> {
                            invokeServiceClear(releaseService);
                            clearReturned.set(true);
                        }
                );
        clearThread.start();

        assertFalse(
                clearReturned.get(),
                "clear() must wait while old release startup "
                        + "holds the lifecycle read permit"
        );

        resumeOld.countDown();
        join(oldRelease);
        join(clearThread);
        blockedRegistry.clear();

        assertTrue(oldReleaseStarted.get());
        assertEquals(1, scheduler.scheduledCount());
        assertTrue(clearReturned.get());
    }

    @Test
    void clearWaitsForRejectedAcquisitionReleaseStartup() {
        PlayerSessionLease lease =
                lease(2L);
        CompletableFuture<Boolean> sharedCompletion =
                new CompletableFuture<>();
        PlayerSessionLeaseBindingRegistry blockedRegistry =
                mock(PlayerSessionLeaseBindingRegistry.class);
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        blockedRegistry,
                        scheduler,
                        logger
                );
        CountDownLatch oldAttached =
                new CountDownLatch(1);
        CountDownLatch resumeOld =
                new CountDownLatch(1);
        AtomicReference<Boolean> clearReturned =
                new AtomicReference<>(false);

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(sharedCompletion);
        when(blockedRegistry
                .reserveRejectedAcquisitionReleaseIfUnbound(lease))
                .thenReturn(true);

        doAnswer(invocation -> {
            oldAttached.countDown();
            await(resumeOld);
            return true;
        }).when(blockedRegistry)
                .attachReleaseCompletion(
                        any(PlayerSessionLease.class),
                        any(CompletableFuture.class)
                );

        Thread oldRelease =
                new Thread(
                        () -> releaseService
                                .releaseRejectedAcquisitionIfUnbound(
                                        lease,
                                        new PlayerSessionReleaseService
                                                .ReleaseCallbacks() {
                                                }
                                )
                );
        oldRelease.start();

        await(oldAttached);

        Thread clearThread =
                new Thread(
                        () -> {
                            invokeServiceClear(releaseService);
                            clearReturned.set(true);
                        }
                );
        clearThread.start();

        assertFalse(clearReturned.get());

        resumeOld.countDown();
        join(oldRelease);
        join(clearThread);

        assertTrue(clearReturned.get());
    }

    @Test
    void alreadyCompletedReleaseStageDoesNotDeadlockStartup() {
        PlayerSessionLease lease =
                lease(2L);
        CompletableFuture<Boolean> completedRelease =
                CompletableFuture.completedFuture(true);
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        registry,
                        scheduler,
                        logger
                );
        AtomicReference<Boolean> callbackReleased =
                new AtomicReference<>();

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(completedRelease);

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                            @Override
                            public void onComplete(
                                    PlayerSessionLease ignored,
                                    boolean released
                            ) {
                                callbackReleased.set(released);
                            }
                        }
                )
        );

        assertEquals(1, scheduler.scheduledCount());
        assertTrue(scheduler.scheduled(0).cancelled());
        assertEquals(Boolean.TRUE, callbackReleased.get());
    }

    @Test
    void retentionTimeoutScheduleFailureExpiresExactQuarantineFailClosed() {
        retentionTimeoutScheduleFailureExpiresExactQuarantineFailClosed(
                ScheduleFailure.THROW
        );
        retentionTimeoutScheduleFailureExpiresExactQuarantineFailClosed(
                ScheduleFailure.NULL
        );
    }

    private void retentionTimeoutScheduleFailureExpiresExactQuarantineFailClosed(
            ScheduleFailure failure
    ) {
        PlayerSessionLeaseBindingRegistry targetRegistry =
                new PlayerSessionLeaseBindingRegistry();
        PlayerSessionLease lease =
                lease(3L);
        CompletableFuture<Boolean> externalCompletion =
                new CompletableFuture<>();
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        targetRegistry,
                        scheduler,
                        logger
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(externalCompletion);

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );

        scheduler.failNextSchedule(failure);
        scheduler.scheduled(0).fire();

        assertEquals(0, targetRegistry.exactQuarantineCount());
        assertEquals(1, scheduler.scheduledCount());
        assertFalse(
                targetRegistry.claimReleaseQuarantineRetentionTimeout(
                        lease,
                        externalCompletion
                )
        );

        externalCompletion.complete(true);

        assertEquals(0, targetRegistry.exactQuarantineCount());
    }

    @Test
    void exactCallbackCancelsRetentionTimeoutBeforeTtl() {
        PlayerSessionLease lease =
                lease(2L);
        CompletableFuture<Boolean> externalCompletion =
                new CompletableFuture<>();
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        registry,
                        scheduler,
                        logger
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(externalCompletion);

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );

        assertEquals(1, scheduler.scheduledCount());
        ManualReleaseTimeoutScheduler.ScheduledTimeout
                ownedTimeout =
                scheduler.scheduled(0);

        assertEquals(
                ReleaseTimeoutPhase.OWNED_RELEASE_TIMEOUT,
                ownedTimeout.key().phase()
        );

        ownedTimeout.fire();

        assertEquals(2, scheduler.scheduledCount());
        ManualReleaseTimeoutScheduler.ScheduledTimeout
                retentionTimeout =
                scheduler.scheduled(1);

        assertEquals(
                ReleaseTimeoutPhase.QUARANTINE_RETENTION_TIMEOUT,
                retentionTimeout.key().phase()
        );
        assertFalse(
                ownedTimeout.key().equals(retentionTimeout.key())
        );
        assertFalse(retentionTimeout.cancelled());

        externalCompletion.complete(true);

        assertTrue(retentionTimeout.cancelled());
        assertEquals(1, retentionTimeout.cancellations());
        assertFalse(registry.reserveReleaseIfUnbound(lease));

        retentionTimeout.fire();

        assertFalse(registry.reserveReleaseIfUnbound(lease));
    }

    @Test
    void retentionTimeoutTaskAfterClearIsInert() {
        PlayerSessionLease lease =
                lease(2L);
        CompletableFuture<Boolean> firstCompletion =
                new CompletableFuture<>();
        CompletableFuture<Boolean> secondCompletion =
                new CompletableFuture<>();
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        registry,
                        scheduler,
                        logger
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(firstCompletion, secondCompletion);

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );

        scheduler.scheduled(0).fire();
        ManualReleaseTimeoutScheduler.ScheduledTimeout
                oldRetentionTimeout =
                scheduler.scheduled(1);

        assertEquals(1, registry.exactQuarantineCount());

        registry.clear();

        assertEquals(0, registry.exactQuarantineCount());

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );

        scheduler.scheduled(2).fire();

        assertEquals(1, registry.exactQuarantineCount());

        oldRetentionTimeout.fire();

        assertEquals(1, registry.exactQuarantineCount());

        secondCompletion.complete(true);

        assertEquals(0, registry.exactQuarantineCount());
    }

    @Test
    void retentionTimeoutTaskAfterClearWithSameLeaseAndStageIsInert() {
        PlayerSessionLease lease =
                lease(2L);
        CompletableFuture<Boolean> sharedCompletion =
                new CompletableFuture<>();
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        registry,
                        scheduler,
                        logger
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(sharedCompletion, sharedCompletion);

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );

        scheduler.scheduled(0).fire();
        ManualReleaseTimeoutScheduler.ScheduledTimeout
                oldRetentionTimeout =
                scheduler.scheduled(1);

        invokeServiceClear(releaseService);
        registry.clear();

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );

        scheduler.scheduled(2).fire();
        ManualReleaseTimeoutScheduler.ScheduledTimeout
                newRetentionTimeout =
                scheduler.scheduled(3);

        assertEquals(1, registry.exactQuarantineCount());

        oldRetentionTimeout.fire();

        assertFalse(newRetentionTimeout.cancelled());
        assertEquals(1, registry.exactQuarantineCount());

        newRetentionTimeout.fire();

        assertEquals(0, registry.exactQuarantineCount());
    }

    @Test
    void quarantineEvictionCancelsExactRetentionHandleImmediately() {
        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        System::nanoTime,
                        16,
                        60_000_000_000L,
                        60_000_000_000L,
                        1,
                        16
                );
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        boundedRegistry,
                        scheduler,
                        logger
                );
        PlayerSessionLease firstLease =
                lease(PLAYER_ID, OWNER, 2L);
        PlayerSessionLease secondLease =
                lease(
                        UUID.fromString(
                                "89f0a2fe-2fde-4920-8dbd-094d98ee94e2"
                        ),
                        OWNER,
                        3L
                );
        CompletableFuture<Boolean> firstCompletion =
                new CompletableFuture<>();
        CompletableFuture<Boolean> secondCompletion =
                new CompletableFuture<>();

        when(coordinator.releaseIfOwned(firstLease))
                .thenReturn(firstCompletion);
        when(coordinator.releaseIfOwned(secondLease))
                .thenReturn(secondCompletion);

        assertTrue(
                releaseService.releaseIfUnbound(
                        firstLease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );
        scheduler.scheduled(0).fire();
        ManualReleaseTimeoutScheduler.ScheduledTimeout
                firstRetention =
                scheduler.scheduled(1);

        assertTrue(
                releaseService.releaseIfUnbound(
                        secondLease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );
        scheduler.scheduled(2).fire();

        assertEquals(1, boundedRegistry.exactQuarantineCount());
        assertTrue(firstRetention.cancelled());

        firstRetention.fire();

        assertEquals(1, boundedRegistry.exactQuarantineCount());
    }

    @Test
    void exactCallbackCancelThrowDoesNotBlockCleanup() {
        PlayerSessionLease lease =
                lease(2L);
        CompletableFuture<Boolean> externalCompletion =
                new CompletableFuture<>();
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        scheduler.throwOnCancel();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        registry,
                        scheduler,
                        logger
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(externalCompletion);

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );

        scheduler.scheduled(0).fire();

        assertEquals(1, registry.exactQuarantineCount());

        externalCompletion.complete(true);

        assertEquals(0, registry.exactQuarantineCount());

        scheduler.scheduled(1).fire();

        assertEquals(0, registry.exactQuarantineCount());
    }

    @Test
    void cancelThrowDuringServiceClearDoesNotBlockInvalidation() {
        PlayerSessionLease lease =
                lease(2L);
        CompletableFuture<Boolean> externalCompletion =
                new CompletableFuture<>();
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        scheduler.throwOnCancel();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        registry,
                        scheduler,
                        logger
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(externalCompletion);

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );
        scheduler.scheduled(0).fire();
        ManualReleaseTimeoutScheduler.ScheduledTimeout retention =
                scheduler.scheduled(1);

        invokeServiceClear(releaseService);
        registry.clear();

        retention.fire();

        assertEquals(0, registry.exactQuarantineCount());
    }

    @Test
    void immediateSchedulerCallbackBeforeScheduleReturnsIsOneShot() {
        PlayerSessionLease lease =
                lease(2L);
        CompletableFuture<Boolean> externalCompletion =
                new CompletableFuture<>();
        ManualReleaseTimeoutScheduler scheduler =
                new ManualReleaseTimeoutScheduler();
        scheduler.fireImmediately();
        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        coordinator,
                        registry,
                        scheduler,
                        logger
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(externalCompletion);

        assertTrue(
                releaseService.releaseIfUnbound(
                        lease,
                        new PlayerSessionReleaseService
                                .ReleaseCallbacks() {
                                }
                )
        );

        assertEquals(0, registry.exactQuarantineCount());
        assertEquals(2, scheduler.scheduledCount());

        scheduler.scheduled(0).fire();
        scheduler.scheduled(1).fire();

        assertEquals(0, registry.exactQuarantineCount());
    }

    private void invokeServiceClear(
            PlayerSessionReleaseService releaseService
    ) {
        try {
            Method method =
                    PlayerSessionReleaseService.class
                            .getDeclaredMethod("clear");

            method.invoke(releaseService);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "release service must expose lifecycle cleanup",
                    exception
            );
        }
    }

    private TestWaiter attachWaiter(
            PlayerSessionLeaseBindingRegistry targetRegistry,
            PlayerSessionLease lease
    ) {
        return attachWaiter(
                targetRegistry,
                player(lease.session().playerId()),
                UUID.randomUUID(),
                lease
        );
    }

    private TestWaiter attachWaiter(
            PlayerSessionLeaseBindingRegistry targetRegistry,
            com.velocitypowered.api.proxy.Player player,
            UUID acquisitionId,
            PlayerSessionLease lease
    ) {
        PlayerSessionLeaseBindingRegistry.BeginResult begin =
                targetRegistry.beginTracked(
                        player,
                        acquisitionId,
                        lease.session()
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );
        assertTrue(
                targetRegistry.claimAcquisitionResult(
                        player,
                        acquisitionId,
                        begin.attemptId()
                )
        );
        assertEquals(
                PlayerSessionLeaseBindingResult.RELEASE_PENDING,
                targetRegistry.bind(
                        player,
                        acquisitionId,
                        begin.attemptId(),
                        lease.session(),
                        lease,
                        new PlayerSessionLeaseBindingRegistry
                                .TerminalAcknowledgement(
                                true,
                                "ok"
                        ),
                        new PlayerSessionLeaseBindingRegistry
                                .TerminalAcknowledgement(
                                false,
                                "fail"
                        )
                )
        );

        return new TestWaiter(
                player,
                acquisitionId,
                targetRegistry.awaitPendingRelease(
                        player,
                        acquisitionId,
                        lease.owner()
                ).orElseThrow()
        );
    }

    private com.velocitypowered.api.proxy.Player player(UUID playerId) {
        com.velocitypowered.api.proxy.Player player =
                mock(com.velocitypowered.api.proxy.Player.class);

        when(player.getUniqueId()).thenReturn(playerId);

        return player;
    }

    private void join(Thread thread) {
        try {
            thread.join(5_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }

        assertFalse(thread.isAlive());
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5L, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private PlayerSessionLease lease(
            UUID playerId,
            ProxyInstanceIdentity owner,
            long fencingToken
    ) {
        return new PlayerSessionLease(
                new AuthenticatedPlayerSession(
                        playerId,
                        "HarriOcho",
                        1_000L
                ),
                owner,
                fencingToken
        );
    }

    private PlayerSessionLease lease(long fencingToken) {
        return lease(PLAYER_ID, OWNER, fencingToken);
    }

    private enum ScheduleFailure {
        THROW,
        NULL
    }

    private static final class EqualCompletionStage
            extends CompletableFuture<Boolean> {

        @Override
        public boolean equals(Object object) {
            return object instanceof EqualCompletionStage;
        }

        @Override
        public int hashCode() {
            return 42;
        }
    }

    private static final class ManualCompletionStage
            extends CompletableFuture<Boolean> {

        private final List<BiConsumer<? super Boolean, ? super Throwable>>
                completions =
                new ArrayList<>();

        @Override
        public CompletableFuture<Boolean> whenComplete(
                BiConsumer<? super Boolean, ? super Throwable> action
        ) {
            completions.add(action);
            return this;
        }

        void trigger(int index, boolean released) {
            completions.get(index).accept(released, null);
        }
    }

    private static final class BlockingLifecycleProbe
            implements PlayerSessionReleaseService.LifecycleProbe {

        private CountDownLatch ownedClaimed =
                new CountDownLatch(0);
        private CountDownLatch releaseOwned =
                new CountDownLatch(0);
        private CountDownLatch externalAccepted =
                new CountDownLatch(0);
        private CountDownLatch releaseExternal =
                new CountDownLatch(0);
        private CountDownLatch retentionClaimed =
                new CountDownLatch(0);
        private CountDownLatch releaseRetention =
                new CountDownLatch(0);

        void blockOwnedTimeoutClaim() {
            ownedClaimed = new CountDownLatch(1);
            releaseOwned = new CountDownLatch(1);
        }

        void awaitOwnedTimeoutClaimed() {
            await(ownedClaimed);
        }

        void releaseOwnedTimeoutClaim() {
            releaseOwned.countDown();
        }

        void blockExternalCallback() {
            externalAccepted = new CountDownLatch(1);
            releaseExternal = new CountDownLatch(1);
        }

        void blockRetentionTimeoutClaim() {
            retentionClaimed = new CountDownLatch(1);
            releaseRetention = new CountDownLatch(1);
        }

        void awaitExternalCallbackAccepted() {
            await(externalAccepted);
        }

        void releaseExternalCallback() {
            releaseExternal.countDown();
        }

        void awaitRetentionTimeoutClaimed() {
            await(retentionClaimed);
        }

        void releaseRetentionTimeoutClaim() {
            releaseRetention.countDown();
        }

        @Override
        public void afterOwnedTimeoutClaimed() {
            ownedClaimed.countDown();
            await(releaseOwned);
        }

        @Override
        public void afterExternalCallbackAccepted() {
            externalAccepted.countDown();
            await(releaseExternal);
        }

        @Override
        public void afterRetentionTimeoutClaimed() {
            retentionClaimed.countDown();
            await(releaseRetention);
        }

        private void await(CountDownLatch latch) {
            try {
                assertTrue(latch.await(5L, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
    }

    private record TestWaiter(
            com.velocitypowered.api.proxy.Player player,
            UUID acquisitionId,
            java.util.concurrent.CompletionStage<Boolean> completion
    ) {
    }

    private static final class ManualReleaseTimeoutScheduler
            implements PlayerSessionReleaseTimeoutScheduler {

        private final List<ScheduledTimeout> scheduled =
                new ArrayList<>();
        private boolean throwOnCancel;
        private ScheduleFailure nextScheduleFailure;
        private boolean fireImmediately;
        private boolean blockNextSchedule;
        private CountDownLatch scheduleEntered =
                new CountDownLatch(1);
        private CountDownLatch releaseSchedule =
                new CountDownLatch(1);

        @Override
        public ScheduledReleaseTimeout schedule(
                ReleaseTimeoutKey key,
                Runnable timeout
        ) {
            ScheduleFailure failure =
                    nextScheduleFailure;
            nextScheduleFailure = null;

            if (blockNextSchedule) {
                blockNextSchedule = false;
                scheduleEntered.countDown();
                await(releaseSchedule);
            }

            if (failure == ScheduleFailure.THROW) {
                throw new IllegalStateException(
                        "schedule failed"
                );
            }

            if (failure == ScheduleFailure.NULL) {
                return null;
            }

            ScheduledTimeout scheduledTimeout =
                    new ScheduledTimeout(
                            key,
                            timeout,
                            throwOnCancel
                    );

            scheduled.add(scheduledTimeout);

            if (fireImmediately) {
                scheduledTimeout.fire();
            }

            return scheduledTimeout;
        }

        int scheduledCount() {
            return scheduled.size();
        }

        ScheduledTimeout scheduled(int index) {
            return scheduled.get(index);
        }

        void throwOnCancel() {
            throwOnCancel = true;
        }

        void failNextSchedule(ScheduleFailure failure) {
            nextScheduleFailure = failure;
        }

        void fireImmediately() {
            fireImmediately = true;
        }

        void blockNextSchedule() {
            blockNextSchedule = true;
            scheduleEntered = new CountDownLatch(1);
            releaseSchedule = new CountDownLatch(1);
        }

        void awaitScheduleEntered() {
            await(scheduleEntered);
        }

        void releaseBlockedSchedule() {
            releaseSchedule.countDown();
        }

        private void await(CountDownLatch latch) {
            try {
                assertTrue(latch.await(5L, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }

        private static final class ScheduledTimeout
                implements ScheduledReleaseTimeout {

            private final ReleaseTimeoutKey key;
            private final Runnable timeout;
            private final boolean throwOnCancel;
            private boolean cancelled;
            private int cancellations;

            private ScheduledTimeout(
                    ReleaseTimeoutKey key,
                    Runnable timeout,
                    boolean throwOnCancel
            ) {
                this.key = key;
                this.timeout = timeout;
                this.throwOnCancel = throwOnCancel;
            }

            void fire() {
                if (!cancelled) {
                    timeout.run();
                }
            }

            boolean cancelled() {
                return cancelled;
            }

            int cancellations() {
                return cancellations;
            }

            ReleaseTimeoutKey key() {
                return key;
            }

            @Override
            public void cancel() {
                cancellations++;
                if (throwOnCancel) {
                    throw new IllegalStateException(
                            "cancel failed"
                    );
                }
                cancelled = true;
            }
        }
    }
}
