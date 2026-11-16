package com.ifels.mixinshim;

import java.util.ArrayList;
import java.util.List;

/** Records everything the shim does to the bootstrap, so a test can assert the order. */
public final class StubCalls {

    public static int clinitCount;

    /**
     * Initialisation count for the probe-only stub. Kept separate from {@link #clinitCount} because
     * that one is shared by every stub test: a class initialises once per JVM, so "it did not
     * initialise" can only be observed on a class no other test has touched.
     */
    public static int probeClinitCount;

    public static final List<String> calls = new ArrayList<String>();

    private StubCalls() {
    }

    public static void reset() {
        calls.clear();
        clinitCount = 0;
        probeClinitCount = 0;
    }
}
