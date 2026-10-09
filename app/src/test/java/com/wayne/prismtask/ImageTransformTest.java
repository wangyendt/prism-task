package com.wayne.prismtask;

import static org.junit.Assert.*;

import org.junit.Test;

public class ImageTransformTest {
  ImageTransform state() {
    ImageTransform s = new ImageTransform();
    s.configure(1000, 2000, 2000, 1000);
    return s;
  }

  @Test
  public void fitsLandscapeInsidePortraitWithoutCropping() {
    ImageTransform s = state();
    assertEquals(.5, s.scale, 1e-9);
    assertEquals(0, s.x, 1e-9);
    assertEquals(750, s.y, 1e-9);
  }

  @Test
  public void zoomUsesFocalPointAndKeepsNonScrollableAxisCentered() {
    ImageTransform s = state();
    s.zoom(2, 500, 1000);
    assertEquals(1, s.scale, 1e-9);
    assertEquals(-500, s.x, 1e-9);
    assertEquals(500, s.y, 1e-9);
    assertTrue(s.zoomed());
  }

  @Test
  public void clampsZoomAndPanningToImageEdges() {
    ImageTransform s = state();
    s.zoom(100, 500, 1000);
    assertEquals(4, s.scale, 1e-9);
    s.pan(1e6, -1e6);
    assertEquals(0, s.x, 1e-9);
    assertEquals(-2000, s.y, 1e-9);
    s.pan(-1e6, 1e6);
    assertEquals(-7000, s.x, 1e-9);
    assertEquals(0, s.y, 1e-9);
  }

  @Test
  public void pinchOutAndResetRestoreFit() {
    ImageTransform s = state();
    s.zoom(5, 100, 200);
    s.zoom(.0001, 100, 200);
    assertEquals(s.fit, s.scale, 1e-9);
    assertFalse(s.zoomed());
    assertEquals(750, s.y, 1e-9);
    s.zoom(2, 500, 1000);
    s.reset();
    assertEquals(0, s.x, 1e-9);
    assertEquals(750, s.y, 1e-9);
  }

  @Test
  public void rotationOrSizeChangeRefits() {
    ImageTransform s = state();
    s.zoom(3, 500, 1000);
    s.configure(1000, 2000, 1000, 2000);
    assertEquals(1, s.scale, 1e-9);
    assertEquals(0, s.x, 1e-9);
    assertEquals(0, s.y, 1e-9);
  }

  @Test
  public void ignoresNonFiniteOrNegativeScaleFactors() {
    ImageTransform s = state();
    s.zoom(Double.NaN, 500, 1000);
    s.zoom(-1, 500, 1000);
    s.zoom(Double.POSITIVE_INFINITY, 0, 0);
    assertEquals(.5, s.scale, 1e-9);
  }
}
