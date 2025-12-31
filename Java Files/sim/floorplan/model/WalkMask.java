package sim.floorplan.model;

import java.awt.image.BufferedImage;

public class WalkMask {
    private final int width;
    private final int height;
    // true = walkable, false = blocked
    private final boolean[] walkable;

    public WalkMask(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid mask size.");
        this.width = width;
        this.height = height;
        this.walkable = new boolean[width * height];
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    public boolean isWalkable(int x, int y) {
        if (!inBounds(x, y)) return false;
        return walkable[y * width + x];
    }

    public void setWalkable(int x, int y, boolean value) {
        if (!inBounds(x, y)) return;
        walkable[y * width + x] = value;
    }

    public void fillWalkable(boolean value) {
        for (int i = 0; i < walkable.length; i++) walkable[i] = value;
    }

    public WalkMask copy() {
        WalkMask c = new WalkMask(width, height);
        System.arraycopy(this.walkable, 0, c.walkable, 0, this.walkable.length);
        return c;
    }

    /**
     * Utility: builds a semi-transparent overlay image.
     * Walkable pixels -> green tint, blocked -> red tint.
     */
    public BufferedImage toOverlayImage(int alpha /*0..255*/) {
        int a = Math.max(0, Math.min(255, alpha));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        // ARGB packed
        int walkARGB  = (a << 24) | (0x00 << 16) | (0xCC << 8) | 0x00; // green-ish
        int blockARGB = (a << 24) | (0xCC << 16) | (0x00 << 8) | 0x00; // red-ish

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, walkable[row + x] ? walkARGB : blockARGB);
            }
        }
        return out;
    }

    /**
     * Save-friendly representation:
     * White = walkable, Black = blocked (TYPE_BYTE_BINARY).
     */
    public BufferedImage toBinaryImage() {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        int white = 0xFFFFFF;
        int black = 0x000000;

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, walkable[row + x] ? white : black);
            }
        }
        return out;
    }

    /**
     * Load from binary image (white-ish => walkable).
     */
    public static WalkMask fromBinaryImage(BufferedImage img) {
        if (img == null) throw new IllegalArgumentException("img is null");
        int w = img.getWidth();
        int h = img.getHeight();
        WalkMask m = new WalkMask(w, h);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                // treat non-black as walkable
                m.setWalkable(x, y, rgb != 0x000000);
            }
        }
        return m;
    }
}
