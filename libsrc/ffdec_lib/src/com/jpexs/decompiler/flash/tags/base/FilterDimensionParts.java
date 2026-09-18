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

import java.util.ArrayList;
import java.util.List;

/**
 * One character's contribution to its own filter dimensions, split from the
 * contribution of the characters it places.
 *
 * A character's filter dimension is the maximum of the growth its own placement
 * filters add and the filter dimensions of everything it places. Both halves are
 * combined with max, which is associative and commutative, so collecting them
 * separately gives the same answer as interleaving them - and lets
 * {@link FilterDimensionsResolver} evaluate a shared subgraph once instead of
 * once per path that reaches it.
 *
 * @author JPEXS
 */
public final class FilterDimensionParts {

    private int localDeltaX;

    private int localDeltaY;

    private final List<DrawableTag> placed = new ArrayList<>();

    /**
     * Adds growth contributed by this character's own filters.
     *
     * @param deltaX Horizontal growth in twips
     * @param deltaY Vertical growth in twips
     */
    public void addLocalDelta(int deltaX, int deltaY) {
        localDeltaX = Math.max(localDeltaX, deltaX);
        localDeltaY = Math.max(localDeltaY, deltaY);
    }

    /**
     * Adds a character this one places. Duplicates are allowed and expected: a
     * sprite placed on many depths is one node of the graph.
     *
     * @param tag Placed character
     */
    public void addPlaced(DrawableTag tag) {
        placed.add(tag);
    }

    /**
     * Gets horizontal growth from this character's own filters.
     *
     * @return Horizontal growth in twips
     */
    public int getLocalDeltaX() {
        return localDeltaX;
    }

    /**
     * Gets vertical growth from this character's own filters.
     *
     * @return Vertical growth in twips
     */
    public int getLocalDeltaY() {
        return localDeltaY;
    }

    /**
     * Gets the characters this one places.
     *
     * @return Placed characters, in placement order, duplicates included
     */
    public List<DrawableTag> getPlaced() {
        return placed;
    }
}
