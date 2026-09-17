/*
 *  Copyright (C) 2010-2026 JPEXS, All rights reserved.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */
package com.jpexs.decompiler.flash.tags.base;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Caps how many decoded lossless bitmaps are held in memory at one time.
 *
 * <p>A lossless bitmap tag carries its pixels zlib-compressed and decodes them on first use. The
 * decoded arrays used to stay on the tag until the whole SWF was dropped, and every tag stays
 * reachable from the SWF, so exporting an image-heavy file retained every bitmap it had already
 * written: 8,218 lossless tags in one measured upload hold 19.13 GiB of decoded pixels, which no
 * worker heap can hold. The compressed source is never dropped, so anything released here can be
 * decoded again on the next use; releasing costs time, not fidelity.</p>
 *
 * <p>Accounting is in decoded <em>bytes</em>, not in number of tags, because the sizes differ by
 * four orders of magnitude within one file. Eviction is least-recently-used over
 * {@link #touch(Holder)}, so a bitmap a renderer keeps coming back to survives while the ones an
 * exporter walked past once do not.</p>
 *
 * <h2>Locking</h2>
 *
 * <p>The one rule: <b>a holder's own lock is never taken while this class's lock is held.</b>
 * Victims are chosen under the lock and released after it has been dropped. A holder may therefore
 * call in from inside its own decode without risking the lock-order inversion that two threads
 * evicting each other's tags would otherwise produce.</p>
 *
 * <p>Holders are referenced weakly. A SWF that is discarded without being cleared leaves entries
 * behind, and those are dropped the next time eviction walks past them; the budget can never be the
 * reason a SWF stays alive.</p>
 *
 * @author JPEXS
 */
public final class DecodedBitmapBudget {

    /**
     * A tag that holds decoded pixel data which can be produced again from bytes it still has.
     */
    public interface Holder {

        /**
         * Drops the decoded pixel arrays. The compressed source must survive this, so that the next
         * use decodes the same pixels again.
         */
        void releaseDecodedBitmap();

        /**
         * Whether the decoded arrays are the only copy of deliberately edited pixels. Such a tag is
         * never evicted: bounding memory must not be able to discard someone's edit.
         *
         * @return True when the decoded data must be kept
         */
        boolean isDecodedBitmapEdited();

        /**
         * Short human-readable identification, used when a single bitmap is refused.
         *
         * @return Description
         */
        String describeDecodedBitmap();
    }

    /**
     * Total decoded bytes kept across all holders.
     */
    public static final String BUDGET_PROPERTY = "ffdec.decodedBitmapBudgetBytes";

    /**
     * Largest single decoded bitmap that will be attempted.
     */
    public static final String MAX_SINGLE_PROPERTY = "ffdec.maxDecodedBitmapBytes";

    private static final long MIB = 1024 * 1024;

    private static final Object LOCK = new Object();

    private static final AtomicLong RELEASES = new AtomicLong();

    /**
     * Access-ordered, so iterating from the front yields least-recently-used first.
     */
    private static final LinkedHashMap<Key, Long> ENTRIES = new LinkedHashMap<>(64, 0.75f, true);

    private static long retainedBytes;

    private static long budgetBytes = configured(BUDGET_PROPERTY, defaultBudgetBytes());

    private static long maxSingleBytes = configured(MAX_SINGLE_PROPERTY, defaultMaxSingleBytes());

    private DecodedBitmapBudget() {
    }

    /**
     * A sixteenth of the heap: large enough that an ordinary SWF never evicts anything and so never
     * decodes a bitmap twice, small enough that the pathological file is bounded far below the heap.
     *
     * @return Default budget in bytes
     */
    private static long defaultBudgetBytes() {
        return clamp(Runtime.getRuntime().maxMemory() / 16, 16 * MIB, 512 * MIB);
    }

    /**
     * Decoding needs room for the inflated stream and the pixel array at once, so the cap on one
     * bitmap is set well inside the heap rather than at it.
     *
     * @return Default single-bitmap ceiling in bytes
     */
    private static long defaultMaxSingleBytes() {
        return clamp(Runtime.getRuntime().maxMemory() / 8, 32 * MIB, 1024 * MIB);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long configured(String property, long fallback) {
        String value = System.getProperty(property);
        if (value == null) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * Gets the total decoded bytes that may be retained across all bitmaps.
     *
     * @return Budget in bytes
     */
    public static long budgetBytes() {
        synchronized (LOCK) {
            return budgetBytes;
        }
    }

    /**
     * Sets the total decoded bytes that may be retained across all bitmaps. Takes effect at once:
     * anything already over the new budget is released.
     *
     * @param bytes Budget in bytes, must be positive
     */
    public static void setBudgetBytes(long bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("budget must be positive");
        }
        List<Holder> victims;
        synchronized (LOCK) {
            budgetBytes = bytes;
            victims = selectVictims(budgetBytes);
        }
        release(victims);
    }

    /**
     * Gets the largest single decoded bitmap that will be attempted.
     *
     * @return Ceiling in bytes
     */
    public static long maxSingleBitmapBytes() {
        synchronized (LOCK) {
            return maxSingleBytes;
        }
    }

    /**
     * Sets the largest single decoded bitmap that will be attempted.
     *
     * @param bytes Ceiling in bytes, must be positive
     */
    public static void setMaxSingleBitmapBytes(long bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("ceiling must be positive");
        }
        synchronized (LOCK) {
            maxSingleBytes = bytes;
        }
    }

    /**
     * Gets the decoded bytes currently retained.
     *
     * @return Retained bytes
     */
    public static long retainedBytes() {
        synchronized (LOCK) {
            return retainedBytes;
        }
    }

    /**
     * Gets the decoded bytes one holder is recorded as retaining. Answers without decoding
     * anything, so it can be asked of a bitmap that has been released.
     *
     * @param holder Holder
     * @return Retained bytes, 0 when it is holding none
     */
    public static long retainedBytesOf(Holder holder) {
        if (holder == null) {
            return 0;
        }
        synchronized (LOCK) {
            Long bytes = ENTRIES.get(new Key(holder));
            return bytes == null ? 0 : bytes;
        }
    }

    /**
     * Gets how many bitmaps are currently holding decoded data.
     *
     * @return Number of holders
     */
    public static int retainedCount() {
        synchronized (LOCK) {
            return ENTRIES.size();
        }
    }

    /**
     * Gets how many bitmaps have been released to stay inside the budget since the JVM started.
     * Zero means nothing was ever decoded twice.
     *
     * @return Number of releases
     */
    public static long releaseCount() {
        return RELEASES.get();
    }

    /**
     * Makes room for a bitmap about to be decoded, and refuses one that could never fit.
     *
     * <p>Refusing is the point: a bitmap whose header claims more pixels than the ceiling would
     * take the whole worker down with it, and one unreadable image is a far better outcome than a
     * killed job that loses every script and asset already written.</p>
     *
     * @param bytes Decoded size the bitmap will occupy
     * @param holder Holder asking, for the message
     * @throws DecodedBitmapTooLargeException When the bitmap cannot be attempted
     */
    public static void reserve(long bytes, Holder holder) {
        if (bytes <= 0) {
            return;
        }
        long ceiling;
        synchronized (LOCK) {
            ceiling = maxSingleBytes;
        }
        if (bytes > ceiling) {
            // Refused from the header alone, so nothing is evicted to make room for a decode that is
            // not going to happen. Outside the lock, because the message comes from the holder.
            throw new DecodedBitmapTooLargeException(
                    holder == null ? "bitmap" : holder.describeDecodedBitmap(), bytes, ceiling);
        }
        List<Holder> victims;
        synchronized (LOCK) {
            // A bitmap larger than the whole budget clears it: the bound is on what is kept, and
            // making the most room available is the best this can do for an image that must decode.
            victims = selectVictims(Math.max(0, budgetBytes - bytes));
        }
        release(victims);
    }

    /**
     * Records that a holder now retains decoded data of this size, evicting others to stay inside
     * the budget.
     *
     * @param holder Holder
     * @param bytes Decoded bytes it retains
     */
    public static void retained(Holder holder, long bytes) {
        if (holder == null || bytes <= 0) {
            return;
        }
        List<Holder> victims;
        synchronized (LOCK) {
            Long previous = ENTRIES.put(new Key(holder), bytes);
            retainedBytes += bytes - (previous == null ? 0 : previous);
            victims = selectVictims(budgetBytes);
        }
        release(victims);
    }

    /**
     * Records a use, so that this holder is among the last to be evicted.
     *
     * @param holder Holder
     */
    public static void touch(Holder holder) {
        if (holder == null) {
            return;
        }
        synchronized (LOCK) {
            ENTRIES.get(new Key(holder));
        }
    }

    /**
     * Records that a holder no longer retains decoded data, whoever dropped it.
     *
     * @param holder Holder
     */
    public static void discard(Holder holder) {
        if (holder == null) {
            return;
        }
        synchronized (LOCK) {
            Long previous = ENTRIES.remove(new Key(holder));
            if (previous != null) {
                retainedBytes -= previous;
            }
        }
    }

    /**
     * Releases every bitmap the budget knows about, edited ones included. For shutting a job down.
     */
    public static void releaseAll() {
        List<Holder> victims = new ArrayList<>();
        synchronized (LOCK) {
            for (Key key : new ArrayList<>(ENTRIES.keySet())) {
                Holder holder = key.get();
                if (holder != null) {
                    victims.add(holder);
                }
            }
            ENTRIES.clear();
            retainedBytes = 0;
        }
        release(victims);
    }

    /**
     * Removes entries until the retained total is at or below the target, oldest use first.
     *
     * <p>Entries are removed and their bytes subtracted here rather than when the holder answers,
     * so that two threads evicting at once cannot pick the same victim twice. A holder that decodes
     * again in between simply registers afresh.</p>
     */
    private static List<Holder> selectVictims(long targetBytes) {
        if (retainedBytes <= targetBytes) {
            return null;
        }
        List<Holder> victims = new ArrayList<>();
        Iterator<Map.Entry<Key, Long>> iterator = ENTRIES.entrySet().iterator();
        while (retainedBytes > targetBytes && iterator.hasNext()) {
            Map.Entry<Key, Long> entry = iterator.next();
            Holder holder = entry.getKey().get();
            if (holder == null) {
                // The SWF was dropped without being cleared; the pixels are already gone.
                retainedBytes -= entry.getValue();
                iterator.remove();
                continue;
            }
            if (holder.isDecodedBitmapEdited()) {
                continue;
            }
            retainedBytes -= entry.getValue();
            iterator.remove();
            victims.add(holder);
        }
        return victims;
    }

    /**
     * Runs outside the lock, because a holder's release takes the holder's own lock.
     */
    private static void release(List<Holder> victims) {
        if (victims == null) {
            return;
        }
        for (Holder victim : victims) {
            RELEASES.incrementAndGet();
            victim.releaseDecodedBitmap();
        }
    }

    /**
     * Identity key over a weakly held holder. Tags do not define equality by identity, and holding
     * one strongly would pin its whole SWF.
     */
    private static final class Key {

        private final WeakReference<Holder> ref;

        private final int hash;

        Key(Holder holder) {
            this.ref = new WeakReference<>(holder);
            this.hash = System.identityHashCode(holder);
        }

        Holder get() {
            return ref.get();
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Key)) {
                return false;
            }
            Holder mine = ref.get();
            return mine != null && mine == ((Key) obj).ref.get();
        }
    }
}
