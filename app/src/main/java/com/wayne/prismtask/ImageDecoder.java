package com.wayne.prismtask;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import java.io.File;
import java.io.IOException;

/** Bounded preview decode; saved/shared files remain untouched originals. */
final class ImageDecoder {
  static Bitmap decode(File file, int target) {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    if (options.outWidth <= 0 || options.outHeight <= 0) return null;
    int sample = 1;
    while (Math.max(options.outWidth, options.outHeight) / sample > target * 2
        || (long) (options.outWidth / sample) * (options.outHeight / sample) > 8_000_000)
      sample *= 2;
    options.inJustDecodeBounds = false;
    options.inSampleSize = sample;
    Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    if (bitmap == null) return null;
    Matrix correction = new Matrix();
    try {
      int orientation =
          new ExifInterface(file.getAbsolutePath())
              .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
      switch (orientation) {
        case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> correction.setScale(-1, 1);
        case ExifInterface.ORIENTATION_ROTATE_180 -> correction.setRotate(180);
        case ExifInterface.ORIENTATION_FLIP_VERTICAL -> correction.setScale(1, -1);
        case ExifInterface.ORIENTATION_TRANSPOSE -> {
          correction.setRotate(90);
          correction.postScale(-1, 1);
        }
        case ExifInterface.ORIENTATION_ROTATE_90 -> correction.setRotate(90);
        case ExifInterface.ORIENTATION_TRANSVERSE -> {
          correction.setRotate(-90);
          correction.postScale(-1, 1);
        }
        case ExifInterface.ORIENTATION_ROTATE_270 -> correction.setRotate(-90);
        default -> {}
      }
    } catch (IOException ignored) {
    }
    if (correction.isIdentity()) return bitmap;
    Bitmap oriented =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), correction, true);
    if (oriented != bitmap) bitmap.recycle();
    return oriented;
  }
}
