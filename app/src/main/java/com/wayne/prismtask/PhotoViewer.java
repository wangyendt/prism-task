package com.wayne.prismtask;

import static com.wayne.prismtask.Visuals.*;

import android.app.Dialog;
import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.core.view.*;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/** Full-screen, local-only gallery. Decoded previews are bounded, originals are never modified. */
final class PhotoViewer extends Dialog {
  final MainActivity host;
  final List<File> files;
  final Consumer<File> save, share;
  int index, generation;
  boolean chromeVisible = true;
  FrameLayout frame;
  LinearLayout top, bottom;
  TextView title, detail, previous, next;
  ZoomImage image;

  PhotoViewer(
      MainActivity host, List<File> files, int index, Consumer<File> save, Consumer<File> share) {
    super(host, android.R.style.Theme_Material_NoActionBar);
    this.host = host;
    this.files = List.copyOf(files);
    this.index = Math.max(0, Math.min(index, files.size() - 1));
    this.save = save;
    this.share = share;
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    frame = new FrameLayout(host);
    frame.setBackgroundColor(BG);
    image = new ZoomImage(host, this::toggleChrome, this::move);
    image.setContentDescription("全屏图片；双指缩放、拖动，双击放大或复位，放大后单击复位");
    frame.addView(image, new FrameLayout.LayoutParams(-1, -1));

    top = host.horizontal();
    top.setPadding(host.dp(12), host.dp(12), host.dp(12), host.dp(12));
    top.setBackgroundColor(0xe6101f2b);
    top.addView(action("关闭", this::dismiss), new LinearLayout.LayoutParams(-2, host.dp(48)));
    LinearLayout heading = host.vertical();
    title = host.text("", 14, INK, true);
    detail = host.mono("正在加载原图预览…", 10, MUTED);
    heading.addView(title);
    host.gap(heading, 5);
    heading.addView(detail);
    LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1);
    headingParams.leftMargin = host.dp(14);
    top.addView(heading, headingParams);
    frame.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

    bottom = host.vertical();
    bottom.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(12));
    bottom.setBackgroundColor(0xe6101f2b);
    LinearLayout gallery = host.horizontal();
    previous = action("上一张", () -> move(-1));
    next = action("下一张", () -> move(1));
    gallery.addView(previous, new LinearLayout.LayoutParams(0, host.dp(48), 1));
    TextView help = host.text("双指缩放 · 拖动\n双击放大/复位 · 单击放大图复位", 10, MUTED, false);
    help.setGravity(Gravity.CENTER);
    gallery.addView(help, new LinearLayout.LayoutParams(0, -2, 3));
    gallery.addView(next, new LinearLayout.LayoutParams(0, host.dp(48), 1));
    bottom.addView(gallery);
    host.gap(bottom, 8);
    LinearLayout tools = host.horizontal();
    addTool(tools, "复位", image::reset);
    addTool(tools, "旋转", image::rotate);
    addTool(tools, "保存", () -> save.accept(files.get(index)));
    addTool(tools, "分享", () -> share.accept(files.get(index)));
    bottom.addView(tools);
    frame.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    setContentView(frame);
    Window window = getWindow();
    if (window != null) {
      window.setLayout(-1, -1);
      window.setBackgroundDrawableResource(android.R.color.transparent);
      WindowCompat.setDecorFitsSystemWindows(window, false);
      WindowInsetsControllerCompat bars = new WindowInsetsControllerCompat(window, frame);
      bars.setAppearanceLightStatusBars(false);
      bars.setAppearanceLightNavigationBars(false);
      bars.setSystemBarsBehavior(
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      bars.hide(WindowInsetsCompat.Type.systemBars());
    }
    ViewCompat.setOnApplyWindowInsetsListener(
        frame,
        (view, insets) -> {
          androidx.core.graphics.Insets safe =
              insets.getInsetsIgnoringVisibility(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          top.setPadding(
              host.dp(12) + safe.left, host.dp(8) + safe.top, host.dp(12) + safe.right, host.dp(8));
          bottom.setPadding(
              host.dp(12) + safe.left,
              host.dp(8),
              host.dp(12) + safe.right,
              host.dp(8) + safe.bottom);
          return insets;
        });
    setOnDismissListener(
        d -> {
          generation++;
          image.clear();
        });
    load();
  }

  TextView action(String text, Runnable action) {
    TextView button = host.text(text, 12, MINT, true);
    button.setGravity(Gravity.CENTER);
    button.setPadding(host.dp(12), 0, host.dp(12), 0);
    host.touch(button, SURFACE, 14, action);
    button.setContentDescription(text);
    return button;
  }

  void addTool(LinearLayout row, String label, Runnable action) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, host.dp(48), 1);
    if (row.getChildCount() > 0) p.leftMargin = host.dp(7);
    row.addView(action(label, action), p);
  }

  void toggleChrome() {
    chromeVisible = !chromeVisible;
    top.setVisibility(chromeVisible ? View.VISIBLE : View.INVISIBLE);
    bottom.setVisibility(chromeVisible ? View.VISIBLE : View.INVISIBLE);
  }

  void move(int step) {
    int nextIndex = index + step;
    if (nextIndex < 0 || nextIndex >= files.size()) return;
    index = nextIndex;
    load();
  }

  void load() {
    int request = ++generation;
    File file = files.get(index);
    image.clear();
    title.setText("图片 " + (index + 1) + " / " + files.size());
    detail.setText("正在加载原图预览…");
    previous.setEnabled(index > 0);
    previous.setAlpha(index > 0 ? 1 : .35f);
    next.setEnabled(index + 1 < files.size());
    next.setAlpha(index + 1 < files.size() ? 1 : .35f);
    int target =
        Math.max(
            host.getResources().getDisplayMetrics().widthPixels,
            host.getResources().getDisplayMetrics().heightPixels);
    host.app.io.execute(
        () -> {
          Bitmap result = null;
          String dimensions = "";
          try {
            BitmapFactory.Options info = new BitmapFactory.Options();
            info.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), info);
            dimensions =
                info.outWidth
                    + " × "
                    + info.outHeight
                    + " px · 原文件 "
                    + Math.max(1, file.length() / 1024)
                    + " KB";
            result = ImageDecoder.decode(file, target);
          } catch (Exception | OutOfMemoryError ignored) {
          }
          Bitmap decoded = result;
          String label = dimensions;
          host.runOnUiThread(
              () -> {
                if (!isShowing() || request != generation || host.isDestroyed()) {
                  if (decoded != null) decoded.recycle();
                  return;
                }
                if (decoded == null) detail.setText("预览读取失败，可保存或分享原文件");
                else {
                  image.setBitmap(decoded);
                  detail.setText(label);
                }
              });
        });
  }

  static final class ZoomImage extends View {
    final ImageTransform transform = new ImageTransform();
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Matrix matrix = new Matrix();
    final ScaleGestureDetector scaleDetector;
    final GestureDetector gestures;
    Bitmap bitmap;
    int rotation;
    boolean multiTouch;

    ZoomImage(Context context, Runnable toggleChrome, Consumer<Integer> swipe) {
      super(context);
      setClickable(true);
      setFocusable(true);
      setOnClickListener(
          v -> {
            if (transform.zoomed()) reset();
            else toggleChrome.run();
          });
      scaleDetector =
          new ScaleGestureDetector(
              context,
              new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                  if (bitmap == null) return false;
                  transform.zoom(
                      detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                  invalidate();
                  return true;
                }
              });
      // Double-tap is handled below, not by ScaleGestureDetector's quick-scale recognizer.
      scaleDetector.setQuickScaleEnabled(false);
      gestures =
          new GestureDetector(
              context,
              new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent event) {
                  return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent event) {
                  performClick();
                  return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent event) {
                  if (bitmap == null) return true;
                  if (transform.zoomed()) reset();
                  else {
                    transform.zoom(2.5, event.getX(), event.getY());
                    invalidate();
                  }
                  return true;
                }

                @Override
                public boolean onScroll(MotionEvent first, MotionEvent last, float dx, float dy) {
                  if (bitmap != null
                      && transform.zoomed()
                      && !scaleDetector.isInProgress()
                      && last.getPointerCount() == 1) {
                    transform.pan(-dx, -dy);
                    invalidate();
                  }
                  return true;
                }

                @Override
                public boolean onFling(MotionEvent first, MotionEvent last, float vx, float vy) {
                  if (first == null || bitmap == null || multiTouch || transform.zoomed())
                    return false;
                  float dx = last.getX() - first.getX(), dy = last.getY() - first.getY();
                  float density = getResources().getDisplayMetrics().density;
                  if (Math.abs(dx) > 60 * density
                      && Math.abs(dx) > Math.abs(dy) * 1.5
                      && Math.abs(vx) > 200 * density) {
                    swipe.accept(dx < 0 ? 1 : -1);
                    return true;
                  }
                  return false;
                }
              });
    }

    void setBitmap(Bitmap bitmap) {
      this.bitmap = bitmap;
      rotation = 0;
      configure();
      invalidate();
    }

    void clear() {
      Bitmap previous = bitmap;
      bitmap = null;
      if (previous != null) previous.recycle();
      invalidate();
    }

    void configure() {
      if (bitmap == null) return;
      boolean swapped = rotation % 180 != 0;
      transform.configure(
          getWidth(),
          getHeight(),
          swapped ? bitmap.getHeight() : bitmap.getWidth(),
          swapped ? bitmap.getWidth() : bitmap.getHeight());
    }

    void reset() {
      transform.reset();
      invalidate();
    }

    void rotate() {
      if (bitmap != null) {
        rotation = (rotation + 90) % 360;
        configure();
        invalidate();
      }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
      configure();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (bitmap == null) return;
      matrix.reset();
      matrix.setRotate(rotation);
      switch (rotation) {
        case 90 -> matrix.postTranslate(bitmap.getHeight(), 0);
        case 180 -> matrix.postTranslate(bitmap.getWidth(), bitmap.getHeight());
        case 270 -> matrix.postTranslate(0, bitmap.getWidth());
        default -> {}
      }
      matrix.postScale((float) transform.scale, (float) transform.scale);
      matrix.postTranslate((float) transform.x, (float) transform.y);
      canvas.drawBitmap(bitmap, matrix, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN) multiTouch = false;
      if (event.getPointerCount() > 1) multiTouch = true;
      scaleDetector.onTouchEvent(event);
      gestures.onTouchEvent(event);
      return true;
    }

    @Override
    public boolean performClick() {
      super.performClick();
      return true;
    }
  }
}
