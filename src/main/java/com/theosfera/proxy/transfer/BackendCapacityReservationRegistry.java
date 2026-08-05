package com.theosfera.proxy.transfer;

/**
 * Compatibility marker for pre-Redis constructor signatures.
 *
 * <p>This type intentionally owns no state and exposes no reserve, release,
 * count, or snapshot operations. Distributed Redis coordination is the only
 * backend-capacity authority in production.</p>
 */
public final class BackendCapacityReservationRegistry {
}
