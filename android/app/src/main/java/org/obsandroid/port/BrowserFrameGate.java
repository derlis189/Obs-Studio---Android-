// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import java.util.concurrent.atomic.AtomicLong;

/** A copied revision must never consume a newer frame that arrived during PixelCopy. */
final class BrowserFrameGate {
    private final AtomicLong changed = new AtomicLong();
    private volatile long delivered;

    void changed() { changed.incrementAndGet(); }
    long revision() { return changed.get(); }
    boolean hasFreshFrame() { return changed.get() > delivered; }
    void delivered(long copiedRevision) { delivered = copiedRevision; }
}
