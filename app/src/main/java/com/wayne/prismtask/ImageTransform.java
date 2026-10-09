package com.wayne.prismtask;

/** Pure image viewport geometry: fit, focal-point zoom and bounded panning. */
public final class ImageTransform {
  public double width, height, imageWidth, imageHeight, fit = 1, scale = 1, x, y;

  public void configure(double width, double height, double imageWidth, double imageHeight) {
    this.width = Math.max(1, width);
    this.height = Math.max(1, height);
    this.imageWidth = Math.max(1, imageWidth);
    this.imageHeight = Math.max(1, imageHeight);
    fit = Math.min(this.width / this.imageWidth, this.height / this.imageHeight);
    reset();
  }

  public void reset() {
    scale = fit;
    x = (width - imageWidth * scale) / 2;
    y = (height - imageHeight * scale) / 2;
  }

  public boolean zoomed() {
    return scale > fit * 1.01;
  }

  public void zoom(double factor, double focusX, double focusY) {
    if (!Double.isFinite(factor) || factor <= 0) return;
    double next = Math.max(fit, Math.min(fit * 8, scale * factor));
    double ratio = next / scale;
    x = focusX - (focusX - x) * ratio;
    y = focusY - (focusY - y) * ratio;
    scale = next;
    constrain();
  }

  public void pan(double dx, double dy) {
    x += dx;
    y += dy;
    constrain();
  }

  private void constrain() {
    double w = imageWidth * scale, h = imageHeight * scale;
    x = w <= width ? (width - w) / 2 : Math.max(width - w, Math.min(0, x));
    y = h <= height ? (height - h) / 2 : Math.max(height - h, Math.min(0, y));
  }
}
