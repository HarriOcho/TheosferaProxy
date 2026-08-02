package com.theosfera.proxy.coordination.distributed.redis;

import java.util.Map;

final class RedisTestcontainersSupport {

    private RedisTestcontainersSupport() {
    }

    static boolean shouldFailWhenDockerUnavailable() {
        return ciRequiresDocker(System.getenv());
    }

    static boolean ciRequiresDocker(Map<String, String> environment) {
        return "true".equalsIgnoreCase(environment.get("CI"));
    }
}
