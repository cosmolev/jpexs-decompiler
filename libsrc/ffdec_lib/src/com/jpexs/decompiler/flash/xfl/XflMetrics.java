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
package com.jpexs.decompiler.flash.xfl;

/** Opt-in diagnostics also work in a standalone JVM without a logging framework. */
final class XflMetrics {
    static void log(String stage, long start, String detail) {
        if (Boolean.getBoolean("decompiler.xfl.metrics")) {
            Runtime runtime = Runtime.getRuntime();
            System.out.printf(java.util.Locale.ROOT, "XFL stage=%s elapsedMs=%.3f heapBytes=%d %s%n",
                    stage, (System.nanoTime() - start) / 1e6,
                    runtime.totalMemory() - runtime.freeMemory(), detail);
        }
    }
}
