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

/**
 * One bitmap declares more decoded pixels than {@link DecodedBitmapBudget} will attempt.
 *
 * <p>Unchecked so that it travels the existing image paths, none of which declare a checked
 * exception. It is raised <em>before</em> anything is allocated, so a caller that catches it has
 * lost only this one image: everything already exported is intact and the export continues.</p>
 *
 * @author JPEXS
 */
public class DecodedBitmapTooLargeException extends RuntimeException {

    private final long requiredBytes;

    private final long limitBytes;

    /**
     * Constructs the exception.
     *
     * @param description What was refused
     * @param requiredBytes Decoded size the bitmap would need
     * @param limitBytes Ceiling in force
     */
    public DecodedBitmapTooLargeException(String description, long requiredBytes, long limitBytes) {
        super(description + " needs " + requiredBytes + " bytes of decoded pixels, above the "
                + limitBytes + " byte limit for a single bitmap");
        this.requiredBytes = requiredBytes;
        this.limitBytes = limitBytes;
    }

    /**
     * Gets the decoded size the bitmap would have needed.
     *
     * @return Bytes
     */
    public long getRequiredBytes() {
        return requiredBytes;
    }

    /**
     * Gets the ceiling that was in force.
     *
     * @return Bytes
     */
    public long getLimitBytes() {
        return limitBytes;
    }
}
