package com.example.ytdl;

/** Guards search pagination from duplicate taps and stale responses. */
final class SearchLoadGate {
    private int generation;
    private boolean moreInProgress;

    synchronized int reset() {
        generation++;
        moreInProgress = false;
        return generation;
    }

    synchronized int current() {
        return generation;
    }

    synchronized boolean isCurrent(final int candidate) {
        return candidate == generation;
    }

    synchronized boolean tryBeginMore() {
        if (moreInProgress) return false;
        moreInProgress = true;
        return true;
    }

    synchronized void finishMore(final int candidate) {
        if (candidate == generation) moreInProgress = false;
    }
}
