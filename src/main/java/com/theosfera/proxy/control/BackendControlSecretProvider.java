package com.theosfera.proxy.control;

import java.util.Optional;

@FunctionalInterface
public interface BackendControlSecretProvider {

    Optional<byte[]> findSecret(String backendName);
}
