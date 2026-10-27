package home.haven.tv;

/**
 * Remembers slow lookups (installed plugins and icon packs, your photo count) between calls, so
 * Settings and the rows don't ask the package manager or storage again on every key press.
 * {@link #changed} forgets them all: called when apps are installed or removed, or photos change.
 */
final class Lookups {

    /** Bumped whenever cached lookups may be out of date. */
    private static volatile int generation;

    private Lookups() { }

    static void changed() {
        generation++;
    }

    static int generation() {
        return generation;
    }
}
