package com.wayne.prismtask;

import android.content.Context;
import android.graphics.*;
import android.text.*;
import android.text.style.*;
import android.view.View;
import java.util.regex.*;

final class Visuals {
  static final int BG = Color.rgb(16, 31, 43),
      SURFACE = Color.rgb(25, 44, 57),
      LINE = Color.rgb(47, 69, 82),
      INK = Color.rgb(239, 246, 246),
      MUTED = Color.rgb(157, 177, 188),
      MINT = Color.rgb(141, 226, 213),
      CORAL = Color.rgb(237, 181, 161);

  static class Icon extends View {
    final String kind;
    int color;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();

    public Icon(Context c) {
      this(c, "spark", MINT);
    }

    Icon(Context c, String kind, int color) {
      super(c);
      this.kind = kind;
      this.color = color;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void stroke(Canvas c, float... p) {
      path.reset();
      path.moveTo(p[0], p[1]);
      for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
      c.drawPath(path, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      canvas.save();
      canvas.translate(getWidth() / 2f - getHeight() / 2f, 0);
      canvas.scale(getHeight() / 24f, getHeight() / 24f);
      paint.setColor(color);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.65f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
      switch (kind) {
        case "pen" -> {
          stroke(canvas, 5, 19, 6, 14, 16, 4, 20, 8, 10, 18, 5, 19);
          stroke(canvas, 14, 6, 18, 10);
        }
        case "image" -> {
          canvas.drawRoundRect(3, 3, 21, 21, 4, 4, paint);
          canvas.drawCircle(8, 8, 1.5f, paint);
          stroke(canvas, 4, 18, 10, 12, 14, 16, 18, 11, 21, 14);
        }
        case "eye" -> {
          Path p = path;
          p.reset();
          p.moveTo(2, 12);
          p.cubicTo(8, 2, 16, 2, 22, 12);
          p.cubicTo(16, 22, 8, 22, 2, 12);
          canvas.drawPath(p, paint);
          canvas.drawCircle(12, 12, 3, paint);
        }
        case "history" -> {
          canvas.drawArc(4, 4, 21, 21, -135, 300, false, paint);
          stroke(canvas, 3, 4, 3, 10, 9, 10);
          stroke(canvas, 12, 8, 12, 13, 16, 15);
        }
        case "settings" -> {
          stroke(canvas, 4, 6, 20, 6);
          stroke(canvas, 4, 12, 20, 12);
          stroke(canvas, 4, 18, 20, 18);
          paint.setStyle(Paint.Style.FILL);
          canvas.drawCircle(9, 6, 2.8f, paint);
          canvas.drawCircle(16, 12, 2.8f, paint);
          canvas.drawCircle(8, 18, 2.8f, paint);
        }
        case "arrow" -> {
          stroke(canvas, 4, 12, 20, 12);
          stroke(canvas, 14, 6, 20, 12, 14, 18);
        }
        case "chevron" -> stroke(canvas, 8, 5, 15, 12, 8, 19);
        case "down" -> stroke(canvas, 6, 9, 12, 15, 18, 9);
        case "refresh" -> {
          canvas.drawArc(4, 4, 20, 20, 30, 285, false, paint);
          stroke(canvas, 20, 4, 20, 10, 14, 10);
        }
        case "plus" -> {
          stroke(canvas, 12, 5, 12, 19);
          stroke(canvas, 5, 12, 19, 12);
        }
        case "close" -> {
          stroke(canvas, 6, 6, 18, 18);
          stroke(canvas, 18, 6, 6, 18);
        }
        case "copy" -> {
          canvas.drawRoundRect(8, 8, 21, 21, 2, 2, paint);
          stroke(canvas, 15, 4, 4, 4, 4, 15);
        }
        case "download" -> {
          stroke(canvas, 12, 3, 12, 16);
          stroke(canvas, 7, 11, 12, 16, 17, 11);
          stroke(canvas, 4, 17, 4, 21, 20, 21, 20, 17);
        }
        case "share" -> {
          canvas.drawCircle(18, 5, 3, paint);
          canvas.drawCircle(6, 12, 3, paint);
          canvas.drawCircle(18, 19, 3, paint);
          stroke(canvas, 9, 10, 15, 6);
          stroke(canvas, 9, 14, 15, 18);
        }
        case "check" -> stroke(canvas, 5, 12, 10, 17, 19, 7);
        default -> {
          stroke(
              canvas, 12, 2, 14.5f, 9.5f, 22, 12, 14.5f, 14.5f, 12, 22, 9.5f, 14.5f, 2, 12, 9.5f,
              9.5f, 12, 2);
        }
      }
      canvas.restore();
    }
  }

  static class Prism extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path a = new Path();
    final RadialGradient glow =
        new RadialGradient(
            0, 0, 75, new int[] {0x254CE3CB, 0x004CE3CB}, null, Shader.TileMode.CLAMP);

    Prism(Context c) {
      super(c);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float w = getWidth(), h = getHeight();
      canvas.save();
      canvas.translate(w / 2f, h / 2f);
      float scale = Math.min(w / 180f, h / 180f);
      canvas.scale(scale, scale);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(.8f);
      p.setColor(LINE);
      canvas.save();
      canvas.rotate(-25);
      canvas.drawOval(-78, -38, 78, 38, p);
      canvas.drawOval(-70, -60, 70, 60, p);
      canvas.restore();
      p.setShader(glow);
      p.setStyle(Paint.Style.FILL);
      canvas.drawCircle(0, 0, 75, p);
      p.setShader(null);
      a.reset();
      a.moveTo(0, -49);
      a.lineTo(-38, 24);
      a.lineTo(0, 47);
      a.close();
      p.setColor(MINT);
      canvas.drawPath(a, p);
      a.reset();
      a.moveTo(0, -49);
      a.lineTo(38, 24);
      a.lineTo(0, 47);
      a.close();
      p.setColor(CORAL);
      canvas.drawPath(a, p);
      a.reset();
      a.moveTo(0, -23);
      a.lineTo(-17, 13);
      a.lineTo(0, 25);
      a.lineTo(17, 13);
      a.close();
      p.setColor(BG);
      canvas.drawPath(a, p);
      p.setColor(MINT);
      canvas.drawCircle(-63, 35, 3, p);
      p.setColor(CORAL);
      canvas.drawCircle(58, -34, 2.5f, p);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1);
      p.setColor(0x609db1bc);
      canvas.drawLine(-84, -57, -64, -57, p);
      canvas.drawLine(-74, -67, -74, -47, p);
      canvas.restore();
    }
  }

  static CharSequence markdown(String text) {
    SpannableStringBuilder out = new SpannableStringBuilder();
    boolean code = false;
    for (String line : text.split("\n", -1)) {
      if (line.startsWith("```")) {
        code = !code;
        continue;
      }
      int start = out.length();
      String shown = line;
      boolean heading = line.matches("^#{1,6} .*"),
          bullet = line.startsWith("- ") || line.startsWith("* ");
      if (heading) shown = line.replaceFirst("^#{1,6} +", "");
      if (bullet) shown = "•  " + line.substring(2);
      shown = shown.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
      out.append(shown).append('\n');
      int end = out.length();
      if (heading) {
        out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(1.15f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(MINT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      if (code) {
        out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }
    return out;
  }
}
