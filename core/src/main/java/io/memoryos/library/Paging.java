package io.memoryos.library;

/** The page bounds every library listing accepts, the ones Chat's own listings use. */
final class Paging {
    private Paging() {}

    static void check(int offset, int limit) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) throw LibraryException.invalid("Invalid page.");
    }
}
