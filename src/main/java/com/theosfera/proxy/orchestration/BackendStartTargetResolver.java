package com.theosfera.proxy.orchestration;

import java.util.Optional;

@FunctionalInterface
public interface BackendStartTargetResolver {

    Optional<BackendStartTarget> resolve(String backendName);
}
