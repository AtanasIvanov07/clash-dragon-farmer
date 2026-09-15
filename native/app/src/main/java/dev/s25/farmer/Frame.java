package dev.s25.farmer;

import android.graphics.Bitmap;

public final class Frame implements Farmer.Screen {
    public final Bitmap bitmap;
    public final int left, top;
    private int[] pixels;
    public Frame(Bitmap bitmap, int left, int top) { this.bitmap = bitmap; this.left = left; this.top = top; }
    public int width() { return bitmap.getWidth(); }
    public int height() { return bitmap.getHeight(); }
    public int[] pixels() {
        if (pixels == null) {
            pixels = new int[width() * height()]; bitmap.getPixels(pixels, 0, width(), 0, 0, width(), height());
        }
        return pixels;
    }
    @Override public void close() { pixels = null; bitmap.recycle(); }
}
