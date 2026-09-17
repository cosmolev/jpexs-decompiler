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

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Spools each completed entry; never retains the complete project as byte arrays. */
final class XflFileStore implements AutoCloseable {
    private final Path directory = Files.createTempDirectory("ffdec-xfl-");
    private final Map<String, Path> entries = new LinkedHashMap<>();
    private long bytes;
    private long writeNanos;

    XflFileStore() throws IOException { }

    void put(String name, byte[] data) {
        long start = System.nanoTime();
        try {
            Path file = entries.get(name);
            long previous = file == null ? 0 : Files.size(file);
            long limit = Long.getLong("decompiler.xfl.maxSpoolBytes", 4L * 1024 * 1024 * 1024);
            if (data.length > limit - (bytes - previous)) throw new IOException("XFL spool limit exceeded");
            if (file == null) {
                file = directory.resolve(Integer.toString(entries.size()));
                entries.put(name, file);
            }
            Files.write(file, data);
            bytes += data.length - previous;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            writeNanos += System.nanoTime() - start;
        }
    }

    int size() { return entries.size(); }
    boolean containsKey(String name) { return entries.containsKey(name); }
    Set<String> keySet() { return entries.keySet(); }
    long length(String name) throws IOException { return Files.size(entries.get(name)); }
    void copyTo(String name, OutputStream out) throws IOException { Files.copy(entries.get(name), out); }
    void copyTo(String name, Path root) throws IOException {
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) throw new IOException("Invalid XFL entry path: " + name);
        Files.createDirectories(target.getParent());
        Files.copy(entries.get(name), target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void close() throws IOException {
        XflMetrics.log("spool", System.nanoTime() - writeNanos, "entries=" + size() + " bytes=" + bytes);
        IOException failure = null;
        for (Path file : entries.values()) {
            try { Files.deleteIfExists(file); } catch (IOException ex) { failure = ex; }
        }
        try { Files.deleteIfExists(directory); } catch (IOException ex) { failure = ex; }
        if (failure != null) throw failure;
    }
}
