package org.example;

/**
 * Where the <b>fixture</b> writes.
 * <p>
 * Second paragraph.
 *
 * @see Object
 */
public class FixtureSettings {

    private static final String DIRECTORY = "/var/data";

    /**
     * Defaults to {@value #DIRECTORY}, see {@link java.time.Duration#ofSeconds(long) a duration}.
     */
    private String directory;

    private int undocumented;

    /**
     * The nested settings.
     */
    public static class Nested {

        /**
         * How long to wait, as {@link java.time.Duration#ofSeconds(long)}.
         */
        private long wait;
    }

    /**
     * The modes.
     */
    public enum Mode {
        FAST;

        /**
         * A field of an enum.
         */
        private final int weight = 1;
    }
}
