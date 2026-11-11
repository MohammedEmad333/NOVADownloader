package com.example.ytdl;

import org.junit.Test;
import static org.junit.Assert.*;

public class SearchLoadGateTest {
    @Test public void duplicateLoadMoreIsRejectedUntilCompletion() {
        final SearchLoadGate gate = new SearchLoadGate();
        final int generation = gate.reset();
        assertTrue(gate.tryBeginMore());
        assertFalse(gate.tryBeginMore());
        gate.finishMore(generation);
        assertTrue(gate.tryBeginMore());
    }

    @Test public void oldSearchResponsesCannotOverrideNewSearch() {
        final SearchLoadGate gate = new SearchLoadGate();
        final int first = gate.reset();
        final int second = gate.reset();
        assertFalse(gate.isCurrent(first));
        assertTrue(gate.isCurrent(second));
    }

    @Test public void stalePageCompletionCannotReleaseCurrentGuard() {
        final SearchLoadGate gate = new SearchLoadGate();
        final int old = gate.reset();
        assertTrue(gate.tryBeginMore());
        final int current = gate.reset();
        assertTrue(gate.tryBeginMore());
        gate.finishMore(old);
        assertFalse(gate.tryBeginMore());
        gate.finishMore(current);
        assertTrue(gate.tryBeginMore());
    }
}
