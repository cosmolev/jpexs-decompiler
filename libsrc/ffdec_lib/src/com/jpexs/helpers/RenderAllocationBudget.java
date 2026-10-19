package com.jpexs.helpers;

import java.awt.image.BufferedImage;

/**
 * An opt-in, thread-local ceiling on raster allocations during one render attempt.
 * Counts cumulative allocations, not estimated live bytes: temporary filter buffers are charged
 * before allocation and never credited back while an attempt is active. This deliberately
 * overestimates live raster memory. It does not bound parser objects, vector geometry or native
 * codec memory. Embedders must retain process isolation and leave heap headroom for those.
 */
public final class RenderAllocationBudget implements AutoCloseable {
    private static final ThreadLocal<RenderAllocationBudget> CURRENT = new ThreadLocal<>();
    private final RenderAllocationBudget parent;
    private final long limit;
    private long used;

    public static final class Exceeded extends RuntimeException {
        public Exceeded(String message) { super(message); }
    }

    private RenderAllocationBudget(long limit) {
        if (limit <= 0) throw new IllegalArgumentException("Render allocation budget must be positive");
        this.limit = limit;
        parent = CURRENT.get();
        CURRENT.set(this);
    }

    public static RenderAllocationBudget open(long bytes) { return new RenderAllocationBudget(bytes); }
    public static boolean active() { return CURRENT.get() != null; }
    public long usedBytes() { return used; }

    public static void charge(long bytes) {
        if (!active()) return;
        for (RenderAllocationBudget b = CURRENT.get(); b != null; b = b.parent) {
            if (bytes < 0 || bytes > b.limit - b.used) {
                throw new Exceeded("Raster allocations exceed the " + b.limit + " byte render budget");
            }
        }
        for (RenderAllocationBudget b = CURRENT.get(); b != null; b = b.parent) b.used += bytes;
    }

    public static void pixels(int width, int height, int bytesPerPixel) {
        if (!active()) return;
        long count = (long) width * height;
        if (width <= 0 || height <= 0 || count > Integer.MAX_VALUE - 8L || bytesPerPixel <= 0) {
            throw new Exceeded("Invalid or oversized raster dimensions: " + width + " x " + height);
        }
        charge(count * bytesPerPixel);
    }

    public static BufferedImage image(int width, int height, int type) {
        pixels(width, height, 4);
        return new BufferedImage(width, height, type);
    }

    public static int[] ints(long count) {
        if (active() && (count < 0 || count > Integer.MAX_VALUE - 8L)) {
            throw new Exceeded("Invalid or oversized raster array: " + count);
        }
        charge(count * 4L);
        return new int[(int) count];
    }

    public static int[] copy(int[] pixels) {
        charge(4L * pixels.length);
        return pixels.clone();
    }

    public static int[] rgb(BufferedImage image) {
        pixels(image.getWidth(), image.getHeight(), 4);
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    @Override public void close() {
        if (CURRENT.get() != this) throw new IllegalStateException("Render budgets must close in order");
        if (parent == null) CURRENT.remove(); else CURRENT.set(parent);
    }
}
