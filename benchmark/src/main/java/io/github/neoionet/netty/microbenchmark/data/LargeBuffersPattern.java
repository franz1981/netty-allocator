package io.github.neoionet.netty.microbenchmark.data;

import java.util.SplittableRandom;

/**
 * Buffers above Netty adaptive's largest size class (132 KiB) only, log-uniform up to 4 MiB, so that every size band
 * gets the same share of allocations; and the same, mixed 2% into {@link ApiGatewayPattern}.
 */
public final class LargeBuffersPattern {
    private static final int MIN = 132 * 1024 + 1;
    private static final int MAX = 4 * 1024 * 1024;

    public static final int[] FLATTENED_SIZE_ARRAY = new int[1 << 17];
    public static final int[] MIXED_WITH_API_GATEWAY = new int[1 << 17];

    static {
        SplittableRandom random = new SplittableRandom(42);
        double logMin = Math.log(MIN);
        double logMax = Math.log(MAX);
        for (int i = 0; i < FLATTENED_SIZE_ARRAY.length; i++) {
            FLATTENED_SIZE_ARRAY[i] = (int) Math.min(MAX, Math.exp(logMin + random.nextDouble() * (logMax - logMin)));
        }
        int[] apiGateway = ApiGatewayPattern.FLATTENED_SIZE_ARRAY;
        for (int i = 0; i < MIXED_WITH_API_GATEWAY.length; i++) {
            MIXED_WITH_API_GATEWAY[i] = random.nextInt(100) < 2 ?
                    FLATTENED_SIZE_ARRAY[random.nextInt(FLATTENED_SIZE_ARRAY.length)] : apiGateway[i];
        }
    }

    private LargeBuffersPattern() {
    }
}
