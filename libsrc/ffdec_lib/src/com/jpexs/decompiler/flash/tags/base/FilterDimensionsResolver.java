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

import com.jpexs.decompiler.flash.SWF;
import java.awt.Dimension;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Evaluates filter dimensions over one SWF's character graph, visiting each
 * character once instead of once per path that reaches it.
 *
 * A sprite's filter dimension is the maximum of the growth its own placement
 * filters add and the filter dimensions of the characters it places, so a
 * character that is placed by many others is asked the same question over and
 * over. Expanding that by hand costs one traversal per distinct path through
 * the graph, which is exponential in the nesting depth of a library that reuses
 * its symbols - the shape every non-trivial Flash game has.
 *
 * Ownership is one resolver per {@link SWF}, so nothing survives the SWF it
 * describes and no character id from one file can be confused with the same id
 * in another. Characters reached across an import boundary keep being answered
 * by their own file's resolver.
 *
 * Results are kept as immutable deltas and handed out as fresh
 * {@link Dimension} objects, because {@code Dimension} is mutable and callers
 * store it in public fields.
 *
 * @author JPEXS
 */
public final class FilterDimensionsResolver {

    /**
     * An immutable resolved filter dimension.
     */
    private static final class Delta {

        final int x;

        final int y;

        Delta(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * One character being evaluated, with the part of its answer known so far.
     */
    private static final class Frame {

        final DrawableTag tag;

        final List<DrawableTag> placed;

        /**
         * Number of cut cycles seen before this character was entered. A
         * character whose own evaluation observed a cut has a path dependent
         * answer and must not be cached.
         */
        final int cutsAtEntry;

        int next;

        int x;

        int y;

        Frame(DrawableTag tag, FilterDimensionParts parts, int cutsAtEntry) {
            this.tag = tag;
            this.placed = parts.getPlaced();
            this.cutsAtEntry = cutsAtEntry;
            this.x = parts.getLocalDeltaX();
            this.y = parts.getLocalDeltaY();
        }

        void grow(int otherX, int otherY) {
            x = Math.max(x, otherX);
            y = Math.max(y, otherY);
        }
    }

    /**
     * Tags have no equals/hashCode of their own, so this is identity keyed.
     */
    private final Map<DrawableTag, Delta> resolved = new ConcurrentHashMap<>();

    private final LongAdder computations = new LongAdder();

    private final SWF swf;

    /**
     * Constructs a resolver for one SWF.
     *
     * @param swf SWF whose characters this resolver answers for
     */
    public FilterDimensionsResolver(SWF swf) {
        this.swf = swf;
    }

    /**
     * Forgets every resolved dimension.
     *
     * A parent's dimension depends on its whole subgraph, so there is no useful
     * single character invalidation: any change to a tag, a filter or a
     * character id can move an answer several levels up. The whole map goes.
     */
    public void clear() {
        resolved.clear();
    }

    /**
     * Gets the number of characters whose dimensions were computed rather than
     * reused. Diagnostics only - this is what tells repeated traversal of a
     * shared subgraph apart from a single pass over it.
     *
     * @return Number of evaluated characters since the last {@link #clear()}
     */
    public long getComputationCount() {
        return computations.sum();
    }

    /**
     * Resolves the filter dimensions of one character.
     *
     * @param tag Character to resolve
     * @param parts Its own contribution and the characters it places
     * @return Filter dimensions, a fresh object the caller may keep or modify
     */
    public Dimension resolve(DrawableTag tag, FilterDimensionParts parts) {
        Delta known = resolved.get(tag);
        if (known != null) {
            return new Dimension(known.x, known.y);
        }
        Delta result = evaluate(tag, parts);
        return new Dimension(result.x, result.y);
    }

    /**
     * Walks the graph below one character with an explicit stack, so a deeply
     * nested library cannot overflow the Java stack.
     */
    private Delta evaluate(DrawableTag root, FilterDimensionParts rootParts) {
        Deque<Frame> stack = new ArrayDeque<>();
        Map<DrawableTag, Boolean> onStack = new IdentityHashMap<>();
        int cuts = 0;

        push(stack, onStack, root, rootParts, cuts);

        while (true) {
            Frame frame = stack.peek();
            if (frame.next < frame.placed.size()) {
                DrawableTag child = frame.placed.get(frame.next++);
                Delta known = resolved.get(child);
                if (known != null) {
                    frame.grow(known.x, known.y);
                    continue;
                }
                if (child.getSwf() != swf) {
                    // An imported character belongs to the file it came from and is
                    // answered - and cached - by that file's resolver.
                    Dimension foreign = child.getFilterDimensions();
                    frame.grow(foreign.width, foreign.height);
                    continue;
                }
                if (onStack.containsKey(child)) {
                    // A cycle that SWF.getCyclicCharacters() did not exclude. Cut it:
                    // the re-entered character contributes nothing here, and every
                    // character still on the stack now has an answer that depends on
                    // where the walk entered the cycle, so none of them are cached.
                    cuts++;
                    continue;
                }
                FilterDimensionParts childParts = child.getFilterDimensionParts();
                if (childParts == null) {
                    // A character that places nothing. Its dimensions are a constant,
                    // so caching it would only make the map bigger.
                    Dimension leaf = child.getFilterDimensions();
                    frame.grow(leaf.width, leaf.height);
                    continue;
                }
                push(stack, onStack, child, childParts, cuts);
                continue;
            }

            stack.pop();
            onStack.remove(frame.tag);
            Delta result = new Delta(frame.x, frame.y);
            if (cuts == frame.cutsAtEntry) {
                resolved.put(frame.tag, result);
            }
            Frame parent = stack.peek();
            if (parent == null) {
                return result;
            }
            parent.grow(result.x, result.y);
        }
    }

    private void push(Deque<Frame> stack, Map<DrawableTag, Boolean> onStack, DrawableTag tag, FilterDimensionParts parts, int cuts) {
        computations.increment();
        stack.push(new Frame(tag, parts, cuts));
        onStack.put(tag, Boolean.TRUE);
    }
}
