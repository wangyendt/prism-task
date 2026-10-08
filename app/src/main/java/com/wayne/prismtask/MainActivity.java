package com.wayne.prismtask;

import static com.wayne.prismtask.Visuals.*;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

/** Native, responsive UI. No WebView or remotely loaded executable content. */
public final class MainActivity extends ComponentActivity implements PrismApp.Listener {
  PrismApp app;
  LinearLayout root, content, nav;
  ScrollView scroll;
  String page = "studio", restoredJobId = "";
  PrismApp.Job selectedJob;
  boolean launching, renderedSubmitting;
  String renderedActiveId = "";
  TextView catalogStatus;
  File exportFile;
  final android.util.LruCache<String, Bitmap> bitmaps =
      new android.util.LruCache<>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
          return bitmap.getByteCount();
        }
      };
  final ActivityResultLauncher<String[]> pickImages =
      registerForActivityResult(
          new ActivityResultContracts.OpenMultipleDocuments(),
          uris -> {
            if (app != null && !uris.isEmpty()) app.importImages(uris);
          });
  final ActivityResultLauncher<String> exportImage =
      registerForActivityResult(
          new ActivityResultContracts.CreateDocument("image/*"),
          uri -> {
            File file = exportFile;
            if (uri == null || file == null) return;
            app.io.execute(
                () -> {
                  try (InputStream in = new FileInputStream(file);
                      OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    runOnUiThread(() -> toast("图片已保存"));
                  } catch (Exception e) {
                    runOnUiThread(() -> toast("图片保存失败，请重新选择保存位置"));
                  }
                });
          });

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    EdgeToEdge.enable(this);
    app = (PrismApp) getApplication();
    if (saved != null) {
      page = saved.getString("page", "studio");
      app.prompt = saved.getString("prompt", app.prompt);
      app.mode = saved.getString("mode", app.mode);
      restoredJobId = saved.getString("jobId", "");
    }
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new androidx.activity.OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                if (page.equals("studio")) finish();
                else navigate(page.equals("result") ? "history" : "studio");
              }
            });
    // Preview is an explicit UI option and never fabricates a successful model request.
    render();
  }

  @Override
  protected void onStart() {
    super.onStart();
    app.listeners.add(this);
    if (!app.preview && !app.refreshing) app.refresh(false);
  }

  @Override
  protected void onStop() {
    app.listeners.remove(this);
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    bitmaps.evictAll();
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putString("page", page);
    out.putString("prompt", app.prompt);
    out.putString("mode", app.mode);
    out.putString("jobId", selectedJob == null ? restoredJobId : selectedJob.id);
    super.onSaveInstanceState(out);
  }

  @Override
  public void changed(String event) {
    if (event.equals("error")) {
      toast(app.notice);
      return;
    }
    if (event.equals("job")) {
      if (launching && app.active != null) {
        selectedJob = app.active;
        page = "result";
        launching = false;
        hideKeyboard();
      }
      if (page.equals("history") || page.equals("result")) renderPreservingScroll();
      else if (page.equals("studio")
          && (renderedSubmitting != app.submitting
              || !Objects.equals(renderedActiveId, app.active == null ? "" : app.active.id)))
        renderPreservingScroll();
    } else if (!page.equals("settings") || event.equals("settings")) renderPreservingScroll();
    else if (catalogStatus != null) catalogStatus.setText(catalogLine());
  }

  int dp(float n) {
    return (int) (getResources().getDisplayMetrics().density * n + .5f);
  }

  LinearLayout vertical() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  LinearLayout horizontal() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  void pad(View v, int n) {
    v.setPadding(dp(n), dp(n), dp(n), dp(n));
  }

  void gap(LinearLayout parent, int height) {
    View v = new View(this);
    parent.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
  }

  void margin(View v, int top) {
    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
    p.topMargin = dp(top);
    v.setLayoutParams(p);
  }

  TextView text(String value, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setIncludeFontPadding(false);
    t.setLineSpacing(dp(3), 1);
    t.setFontFeatureSettings("kern");
    t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    return t;
  }

  TextView mono(String value, int size, int color) {
    TextView t = text(value, size, color, false);
    t.setTypeface(Typeface.MONOSPACE);
    return t;
  }

  GradientDrawable shape(int color, int radius, boolean border) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    if (border) d.setStroke(dp(1), LINE);
    return d;
  }

  void touch(View v, int color, int radius, Runnable action) {
    v.setBackground(
        new RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x338de2d5),
            shape(color, radius, false),
            null));
    v.setMinimumHeight(dp(48));
    v.setFocusable(true);
    v.setOnClickListener(x -> action.run());
  }

  LinearLayout card(LinearLayout parent) {
    LinearLayout c = vertical();
    pad(c, 20);
    c.setBackground(shape(SURFACE, 24, true));
    parent.addView(c, new LinearLayout.LayoutParams(-1, -2));
    return c;
  }

  LinearLayout button(String label, String icon, boolean primary, Runnable action) {
    LinearLayout b = horizontal();
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(16), dp(13), dp(16), dp(13));
    if (icon != null) {
      b.addView(
          new Icon(this, icon, primary ? BG : MINT), new LinearLayout.LayoutParams(dp(20), dp(20)));
      View space = new View(this);
      b.addView(space, new LinearLayout.LayoutParams(dp(9), 1));
    }
    b.addView(text(label, 14, primary ? BG : INK, true));
    touch(b, primary ? MINT : 0xff213b4a, 16, action);
    b.setContentDescription(label);
    return b;
  }

  TextView pill(String label, boolean active, Runnable action) {
    TextView p = text(label, 13, active ? BG : MUTED, active);
    p.setGravity(Gravity.CENTER);
    p.setPadding(dp(15), dp(11), dp(15), dp(11));
    touch(p, active ? MINT : 0xff213643, 12, action);
    return p;
  }

  void eyebrow(LinearLayout parent, String title) {
    TextView t = mono(title, 11, MUTED);
    t.setLetterSpacing(.1f);
    parent.addView(t);
    gap(parent, 12);
  }

  void hint(LinearLayout parent, String message, int color) {
    TextView t = text(message, 12, color, false);
    t.setLineSpacing(dp(5), 1);
    parent.addView(t);
  }

  void renderPreservingScroll() {
    int y = scroll == null ? 0 : scroll.getScrollY();
    render();
    scroll.post(() -> scroll.scrollTo(0, y));
  }

  void navigate(String destination) {
    hideKeyboard();
    page = destination;
    selectedJob = null;
    restoredJobId = "";
    render();
  }

  void render() {
    if (isFinishing()) return;
    renderedSubmitting = app.submitting;
    renderedActiveId = app.active == null ? "" : app.active.id;
    root = vertical();
    root.setBackgroundColor(BG);
    setContentView(root);
    androidx.core.view.WindowInsetsControllerCompat barController =
        new androidx.core.view.WindowInsetsControllerCompat(getWindow(), root);
    barController.setAppearanceLightStatusBars(false);
    barController.setAppearanceLightNavigationBars(false);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (view, insets) -> {
          Insets bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
          view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
          return insets;
        });
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    // Keep composition readable on both narrow phones and wide tablets.
    LinearLayout center = horizontal();
    center.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    scroll.addView(center, new ScrollView.LayoutParams(-1, -2));
    content = vertical();
    int width = Math.min(getResources().getDisplayMetrics().widthPixels, dp(680));
    center.addView(content, new LinearLayout.LayoutParams(width, -2));
    content.setPadding(dp(24), dp(20), dp(24), dp(28));
    header();
    gap(content, 28);
    switch (page) {
      case "settings" -> settings();
      case "history" -> history();
      case "result" -> result();
      default -> studio();
    }
    bottomNav();
  }

  void header() {
    LinearLayout row = horizontal();
    row.addView(new Prism(this), new LinearLayout.LayoutParams(dp(32), dp(36)));
    View gap = new View(this);
    row.addView(gap, new LinearLayout.LayoutParams(dp(9), 1));
    TextView brand = text("prism", 25, INK, true);
    brand.setLetterSpacing(-.04f);
    row.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    String state =
        app.preview
            ? "界面预览"
            : app.catalog != null && !app.localCatalog && !app.catalog.stale()
                ? "目录已连接"
                : app.hasToken() ? "设备已配置" : "尚未连接";
    TextView badge = text("●  " + state, 11, app.preview ? CORAL : MINT, false);
    badge.setPadding(dp(12), dp(9), dp(12), dp(9));
    touch(badge, 0xff1d3642, 20, () -> navigate("settings"));
    row.addView(badge);
    content.addView(row);
  }

  void bottomNav() {
    View rule = new View(this);
    rule.setBackgroundColor(LINE);
    root.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
    nav = horizontal();
    nav.setPadding(dp(12), dp(9), dp(12), dp(9));
    String[] pages = {"studio", "history", "settings"},
        names = {"创作", "任务", "连接"},
        icons = {"spark", "history", "settings"};
    for (int i = 0; i < 3; i++) {
      String route = pages[i];
      boolean active = page.equals(route) || page.equals("result") && route.equals("history");
      LinearLayout item = vertical();
      item.setGravity(Gravity.CENTER);
      pad(item, 8);
      item.addView(
          new Icon(this, icons[i], active ? MINT : MUTED),
          new LinearLayout.LayoutParams(dp(22), dp(22)));
      gap(item, 6);
      item.addView(text(names[i], 11, active ? MINT : MUTED, active));
      touch(item, active ? SURFACE : BG, 16, () -> navigate(route));
      item.setContentDescription(names[i] + "页面");
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(62), 1);
      params.leftMargin = dp(4);
      params.rightMargin = dp(4);
      nav.addView(item, params);
    }
    root.addView(nav, new LinearLayout.LayoutParams(-1, -2));
  }

  void studio() {
    LinearLayout hero = horizontal();
    LinearLayout words = vertical();
    words.addView(mono("CODEX TASK / MOBILE STUDIO", 10, MINT));
    gap(words, 12);
    TextView headline = text("一个想法。\n三种可能。", 32, INK, true);
    headline.setLetterSpacing(-.035f);
    words.addView(headline);
    gap(words, 10);
    words.addView(text("写下来，画出来，看明白。", 12, MUTED, false));
    hero.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    hero.addView(new Prism(this), new LinearLayout.LayoutParams(dp(112), dp(150)));
    content.addView(hero);
    gap(content, 24);
    LinearLayout modes = horizontal();
    String[] ids = {"text", "image", "vision"},
        labels = {"生文", "生图", "识图"},
        icons = {"pen", "image", "eye"};
    for (int i = 0; i < 3; i++) {
      String id = ids[i];
      boolean chosen = app.mode.equals(id);
      LinearLayout chip = horizontal();
      chip.setGravity(Gravity.CENTER);
      chip.setPadding(dp(8), dp(14), dp(8), dp(14));
      chip.addView(
          new Icon(this, icons[i], chosen ? BG : MUTED),
          new LinearLayout.LayoutParams(dp(20), dp(20)));
      View g = new View(this);
      chip.addView(g, new LinearLayout.LayoutParams(dp(7), 1));
      chip.addView(text(labels[i], 14, chosen ? BG : MUTED, true));
      touch(
          chip,
          chosen ? MINT : SURFACE,
          16,
          () -> {
            app.mode = id;
            renderPreservingScroll();
          });
      chip.setContentDescription(labels[i] + (chosen ? "，已选择" : ""));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1);
      if (i > 0) p.leftMargin = dp(8);
      modes.addView(chip, p);
    }
    content.addView(modes);
    gap(content, 20);
    modelControls(content);
    gap(content, 16);
    LinearLayout composer = card(content);
    eyebrow(composer, app.mode.equals("vision") ? "PROMPT / 你想了解什么" : "PROMPT / 描述你的想法");
    EditText input = new EditText(this);
    input.setTextSize(16);
    input.setTextColor(INK);
    input.setHintTextColor(0xff718b9c);
    input.setGravity(Gravity.TOP);
    input.setMinLines(4);
    input.setMaxLines(10);
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    input.setPadding(0, 0, 0, dp(8));
    input.setBackgroundColor(Color.TRANSPARENT);
    input.setHint(
        switch (app.mode) {
          case "image" -> "例：一座漂浮在云海里的玻璃温室，清晨，自然光，电影感。";
          case "vision" -> "例：识别这张图中的物品，并解释画面的构图与配色。";
          default -> "例：写一段关于海边清晨的短文，克制、细腻，120 字以内。";
        });
    input.setText(app.prompt);
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            app.prompt = s.toString();
          }

          public void afterTextChanged(Editable e) {}
        });
    composer.addView(input, new LinearLayout.LayoutParams(-1, -2));
    gap(composer, 8);
    attachments(composer);
    if (app.mode.equals("image")) {
      gap(composer, 18);
      imageSettings(composer);
    }
    gap(content, 16);
    String invalid = app.selectionError();
    if (invalid != null && !app.model.isBlank()) {
      hint(content, invalid, CORAL);
      gap(content, 12);
    }
    if (app.preview) {
      hint(content, "界面预览使用演示目录，不会创建真实任务。", CORAL);
      gap(content, 12);
    }
    if (!app.connectionError.isEmpty()) {
      hint(content, app.connectionError, CORAL);
      gap(content, 12);
    }
    String action =
        app.submitting
            ? "正在提交…"
            : app.active != null
                ? "查看运行中的任务"
                : switch (app.mode) {
                  case "image" -> "开始生成图片";
                  case "vision" -> "开始识图";
                  default -> "开始生成文字";
                };
    LinearLayout submit =
        button(
            action,
            "arrow",
            true,
            () -> {
              if (app.active != null) {
                selectedJob = app.active;
                page = "result";
                render();
                return;
              }
              launching = true;
              app.submit();
              if (!app.submitting) launching = false;
            });
    if (app.submitting || app.importing) {
      submit.setAlpha(.55f);
      submit.setEnabled(false);
    }
    content.addView(submit, new LinearLayout.LayoutParams(-1, dp(56)));
    gap(content, 12);
    TextView footer =
        text(
            app.mode.equals("image") ? "这里选择的是生图主模型；托管绘图工具的具体版本未披露。" : "DIRECT 后端 · 模型与等级由服务动态提供",
            11,
            MUTED,
            false);
    footer.setGravity(Gravity.CENTER);
    content.addView(footer);
  }

  String catalogLine() {
    if (app.refreshing) return "正在拉取模型与思考等级…";
    if (app.catalog == null)
      return app.connectionError.isBlank() ? "尚无目录 · 前往连接页配置 Token" : app.connectionError;
    String state =
        app.preview
            ? "预览数据"
            : app.localCatalog ? "本地缓存" : app.catalog.stale() ? "旧目录 · 服务降级" : "实时目录";
    return state
        + " · "
        + app.catalog.models().size()
        + " 个模型"
        + (app.catalog.updatedAt() == null ? "" : " · " + formatIso(app.catalog.updatedAt()));
  }

  void modelControls(LinearLayout parent) {
    LinearLayout box = card(parent);
    eyebrow(box, "MODEL / 模型");
    LinearLayout row = horizontal();
    LinearLayout labels = vertical();
    Protocol.Model m = app.catalog == null ? null : app.catalog.find(app.model);
    labels.addView(
        text(
            app.model.isBlank()
                ? "选择模型"
                : app.manual ? app.model : m == null ? app.model : m.name(),
            18,
            INK,
            true));
    gap(labels, 6);
    TextView id = mono(app.model.isBlank() ? "来自你的 CodexTask 服务" : app.model, 11, MUTED);
    id.setMaxLines(2);
    labels.addView(id);
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(new Icon(this, "down", MINT), new LinearLayout.LayoutParams(dp(22), dp(22)));
    touch(row, SURFACE, 8, this::modelDialog);
    box.addView(row);
    gap(box, 18);
    eyebrow(box, "REASONING / 思考等级");
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    LinearLayout levels = horizontal();
    addLevel(levels, "服务默认", null);
    if (!app.manual && m != null) for (String value : m.levels()) addLevel(levels, value, value);
    if (app.manual) {
      TextView custom =
          pill(
              app.selectedEffort() == null ? "自定义等级 +" : app.selectedEffort(),
              app.selectedEffort() != null,
              this::effortDialog);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
      p.leftMargin = dp(8);
      levels.addView(custom, p);
    }
    hs.addView(levels);
    box.addView(hs);
    gap(box, 12);
    String note =
        app.selectedEffort() == null
            ? "省略 reasoning，使用服务自己的默认行为。"
            : m != null && Objects.equals(m.defaultLevel(), app.selectedEffort())
                ? "当前为模型声明的默认等级，提交时将明确传入。"
                : "将明确传入 " + app.selectedEffort() + "；保留服务返回的选项顺序。";
    if (m != null && m.levels().isEmpty()) note = "该模型没有等级选项，提交时省略 reasoning。";
    hint(box, note, MUTED);
    gap(box, 14);
    catalogStatus =
        text(
            catalogLine(),
            10,
            app.catalog != null && (app.localCatalog || app.catalog.stale()) ? CORAL : MUTED,
            false);
    box.addView(catalogStatus);
  }

  void addLevel(LinearLayout row, String label, String value) {
    TextView p =
        pill(label, Objects.equals(app.selectedEffort(), value), () -> app.chooseEffort(value));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
    if (row.getChildCount() > 0) params.leftMargin = dp(8);
    row.addView(p, params);
  }

  void modelDialog() {
    LinearLayout body = vertical();
    pad(body, 20);
    if (app.catalog != null)
      for (Protocol.Model m : app.catalog.models()) {
        LinearLayout row = vertical();
        pad(row, 14);
        row.addView(text(m.name(), 16, INK, true));
        gap(row, 7);
        row.addView(mono(m.id(), 11, MUTED));
        gap(row, 6);
        row.addView(text(String.join(" · ", m.levels()), 11, MUTED, false));
        gap(row, 6);
        row.addView(mono("输入 · " + String.join(" + ", m.modalities()), 10, MINT));
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        gap(body, 7);
        row.setTag(m.id());
      }
    hint(body, "image 表示支持图片输入，不代表图片生成能力。", MUTED);
    gap(body, 14);
    ScrollView sc = new ScrollView(this);
    sc.addView(body);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("选择模型")
            .setNeutralButton("手动 ID", (d, w) -> manualDialog())
            .setView(sc)
            .setNegativeButton("关闭", null)
            .create();
    for (int i = 0; i < body.getChildCount(); i++) {
      View row = body.getChildAt(i);
      if (row.getTag() instanceof String id)
        touch(
            row,
            app.model.equals(id) ? 0xff284b56 : SURFACE,
            14,
            () -> {
              dialog.dismiss();
              app.select(id, false);
            });
    }
    dialog.show();
  }

  EditText field(String value, String hint, boolean password) {
    EditText t = new EditText(this);
    t.setTextColor(INK);
    t.setHintTextColor(MUTED);
    t.setTextSize(14);
    t.setSingleLine(true);
    t.setPadding(dp(14), dp(14), dp(14), dp(14));
    t.setBackground(shape(BG, 12, true));
    t.setHint(hint);
    t.setText(value);
    t.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | (password
                ? android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                : android.text.InputType.TYPE_TEXT_VARIATION_URI));
    t.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    if (password) t.setTransformationMethod(PasswordTransformationMethod.getInstance());
    return t;
  }

  void manualDialog() {
    LinearLayout body = vertical();
    pad(body, 24);
    EditText input = field(app.manual ? app.model : "", "model ID", false);
    body.addView(input);
    gap(body, 12);
    hint(body, "目录尚未收录的模型可手动测试。思考等级默认省略，也可显式输入。服务仍可能拒绝未支持的组合。", MUTED);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("手动模型 ID")
            .setView(body)
            .setNegativeButton("取消", null)
            .setPositiveButton("使用", null)
            .create();
    d.setOnShowListener(
        v ->
            d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    x -> {
                      String id = input.getText().toString().trim();
                      if (id.isBlank() || id.length() > 128 || !id.matches("[a-zA-Z0-9._:/-]+")) {
                        input.setError("请输入有效模型 ID");
                        return;
                      }
                      app.select(id, true);
                      d.dismiss();
                    }));
    d.show();
  }

  void effortDialog() {
    LinearLayout body = vertical();
    pad(body, 24);
    EditText input =
        field(app.selectedEffort() == null ? "" : app.selectedEffort(), "等级字符串（留空使用服务默认）", false);
    body.addView(input);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("手动思考等级")
            .setView(body)
            .setNegativeButton("取消", null)
            .setPositiveButton("使用", null)
            .create();
    d.setOnShowListener(
        v ->
            d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    x -> {
                      String value = input.getText().toString().trim();
                      if (!value.isEmpty() && !value.matches("[a-z][a-z0-9_-]{0,31}")) {
                        input.setError("使用小写等级字符串");
                        return;
                      }
                      app.chooseEffort(value.isEmpty() ? null : value);
                      d.dismiss();
                    }));
    d.show();
  }

  void attachments(LinearLayout parent) {
    LinearLayout line = horizontal();
    LinearLayout add =
        button(
            app.importing ? "正在读取…" : app.inputs.isEmpty() ? "添加参考图片" : "再加一张",
            "plus",
            false,
            () ->
                pickImages.launch(
                    new String[] {"image/png", "image/jpeg", "image/webp", "image/gif"}));
    add.setEnabled(!app.importing);
    line.addView(add);
    TextView count = text(app.inputs.isEmpty() ? "可选" : app.inputs.size() + " 张", 11, MUTED, false);
    count.setGravity(Gravity.END);
    line.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
    parent.addView(line);
    if (!app.inputs.isEmpty()) {
      gap(parent, 12);
      HorizontalScrollView hs = new HorizontalScrollView(this);
      hs.setHorizontalScrollBarEnabled(false);
      LinearLayout images = horizontal();
      for (PrismApp.InputImage ref : app.inputs) {
        LinearLayout tile = vertical();
        ImageView image = imageView(ref.file(), 100);
        tile.addView(image, new LinearLayout.LayoutParams(dp(100), dp(80)));
        TextView remove = text("移除 ×", 11, CORAL, false);
        remove.setGravity(Gravity.CENTER);
        touch(remove, BG, 10, () -> app.removeInput(ref));
        tile.addView(remove, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(100), -2);
        p.rightMargin = dp(10);
        images.addView(tile, p);
      }
      hs.addView(images);
      parent.addView(hs);
    }
    gap(parent, 9);
    hint(parent, "单张 ≤ 20 MiB · 合计 ≤ 50 MiB · 不压缩原图", MUTED);
  }

  void imageSettings(LinearLayout parent) {
    eyebrow(parent, "OUTPUT / 图片设置");
    LinearLayout row = horizontal();
    TextView size =
        pill(
            app.size,
            false,
            () ->
                options(
                    "输出尺寸",
                    new String[] {"1024x1024", "1024x1536", "1536x1024", "auto"},
                    value -> {
                      app.size = value;
                      renderPreservingScroll();
                    }));
    row.addView(size, new LinearLayout.LayoutParams(0, -2, 1));
    TextView quality =
        pill(
            "质量 · " + app.quality,
            false,
            () ->
                options(
                    "生成质量",
                    new String[] {"auto", "low", "medium", "high"},
                    value -> {
                      app.quality = value;
                      renderPreservingScroll();
                    }));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.leftMargin = dp(8);
    row.addView(quality, p);
    parent.addView(row);
  }

  interface Choice {
    void pick(String value);
  }

  void options(String title, String[] choices, Choice callback) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(choices, (d, i) -> callback.pick(choices[i]))
        .setNegativeButton("取消", null)
        .show();
  }

  void title(String eyebrow, String name, String description) {
    eyebrow(content, eyebrow);
    content.addView(text(name, 30, INK, true));
    gap(content, 12);
    hint(content, description, MUTED);
    gap(content, 24);
  }

  void settings() {
    title("CONNECTION / 设备连接", "连接你的工作台", "配置一次，在手机上测试服务里的模型与思考等级。");
    LinearLayout box = card(content);
    eyebrow(box, "SERVICE / 服务地址");
    EditText endpoint = field(app.base, "https://HOST/services/codex-task", false);
    endpoint.setContentDescription("服务地址");
    box.addView(endpoint, new LinearLayout.LayoutParams(-1, -2));
    gap(box, 18);
    eyebrow(box, "TOKEN / 设备凭证");
    EditText token =
        field("", app.hasToken() ? "已安全保存 · 输入新 Token 可替换" : "粘贴 text,image 设备 Token", true);
    token.setContentDescription("设备 Token");
    box.addView(token, new LinearLayout.LayoutParams(-1, -2));
    gap(box, 12);
    hint(box, "Token 由 Android Keystore 加密。留空保留当前凭证；更换服务时需要重新输入。", MUTED);
    gap(box, 20);
    box.addView(
        button(
            "保存并连接",
            "arrow",
            true,
            () -> {
              String value = token.getText().toString().trim();
              boolean result =
                  app.configure(endpoint.getText().toString(), value.isBlank() ? null : value);
              if (result) {
                token.setText("");
                hideKeyboard();
              }
            }),
        new LinearLayout.LayoutParams(-1, dp(52)));
    gap(content, 16);
    LinearLayout catalog = card(content);
    eyebrow(catalog, "CATALOG / 模型目录");
    catalogStatus = text(catalogLine(), 13, INK, false);
    catalog.addView(catalogStatus);
    gap(catalog, 14);
    if (app.catalog != null) {
      info(catalog, "来源", app.catalog.source());
      info(
          catalog,
          "成功查询时间",
          app.catalog.updatedAt() == null ? "暂无" : formatIso(app.catalog.updatedAt()));
    }
    if (!app.connectionError.isBlank()) {
      hint(catalog, app.connectionError, CORAL);
      gap(catalog, 12);
    }
    catalog.addView(
        button(app.refreshing ? "正在刷新…" : "刷新模型目录", "refresh", false, () -> app.refresh(true)),
        new LinearLayout.LayoutParams(-1, dp(48)));
    gap(catalog, 10);
    hint(catalog, "普通查询缓存 5 分钟，手动刷新间隔至少 15 秒。失败时保留已有选择，不会静默更换等级。", MUTED);
    gap(content, 16);
    LinearLayout privacy = card(content);
    eyebrow(privacy, "ON DEVICE / 本机数据");
    hint(
        privacy, "最近 30 条任务保存在本机，图片产物下载后可离线查看。仅在 App 进程存活时轮询；重新打开会恢复未完成任务。服务端记录通常保留 24 小时。", MUTED);
    gap(privacy, 16);
    privacy.addView(
        button(
            "清除本机任务记录",
            "history",
            false,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("清除本机记录？")
                    .setMessage("同时删除已下载的图片，不会删除服务端任务。")
                    .setNegativeButton("保留", null)
                    .setPositiveButton("清除", (d, w) -> app.clearHistory())
                    .show()));
    gap(privacy, 10);
    privacy.addView(
        button(
            "移除设备 Token",
            "close",
            false,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("移除本机 Token？")
                    .setMessage("服务端凭证不会被吊销。重新填写后可继续使用。")
                    .setNegativeButton("保留", null)
                    .setPositiveButton(
                        "移除",
                        (d, w) -> {
                          if (app.active != null) {
                            toast("请先等待任务结束");
                            return;
                          }
                          app.configure(app.base, "");
                        })
                    .show()));
    gap(content, 20);
    content.addView(
        button(
            app.preview ? "退出界面预览" : "查看界面预览",
            "eye",
            false,
            () -> {
              app.enterPreview(!app.preview);
              if (app.preview) navigate("studio");
            }),
        new LinearLayout.LayoutParams(-1, dp(48)));
    gap(content, 22);
    TextView footer = mono("PRISM TASK  0.1.0  /  DIRECT ONLY", 10, MUTED);
    footer.setGravity(Gravity.CENTER);
    content.addView(footer);
  }

  void info(LinearLayout parent, String label, String value) {
    LinearLayout row = horizontal();
    TextView k = text(label, 11, MUTED, false);
    row.addView(k);
    TextView v = mono(value, 11, INK);
    v.setGravity(Gravity.END);
    v.setMaxLines(3);
    row.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
    parent.addView(row);
    gap(parent, 12);
  }

  void history() {
    title("RUNS / 任务记录", "每一次尝试", "查看真实任务状态、模型选择与本机保存的结果。");
    if (app.jobs.isEmpty()) {
      LinearLayout empty = card(content);
      empty.setGravity(Gravity.CENTER);
      empty.addView(new Prism(this), new LinearLayout.LayoutParams(dp(150), dp(150)));
      gap(empty, 12);
      empty.addView(text("第一次创作，从这里开始", 19, INK, true));
      gap(empty, 12);
      hint(empty, "提交任务后，文字与图片结果会出现在这里。", MUTED);
      gap(empty, 24);
      empty.addView(button("去创作", "arrow", true, () -> navigate("studio")));
      return;
    }
    for (PrismApp.Job job : app.jobs) {
      LinearLayout card = card(content);
      LinearLayout top = horizontal();
      String icon = job.mode.equals("image") ? "image" : job.mode.equals("vision") ? "eye" : "pen";
      top.addView(new Icon(this, icon, MINT), new LinearLayout.LayoutParams(dp(22), dp(22)));
      TextView type = text("  " + modeLabel(job.mode), 12, MUTED, false);
      top.addView(type, new LinearLayout.LayoutParams(0, -2, 1));
      top.addView(
          text(
              statusLabel(job.status),
              11,
              Protocol.terminal(job.status) && !job.status.equals("completed") ? CORAL : MINT,
              true));
      card.addView(top);
      gap(card, 16);
      card.addView(text(job.title(), 17, INK, true));
      gap(card, 12);
      TextView meta =
          mono(
              job.model + " / " + (job.effort == null ? "service default" : job.effort), 10, MUTED);
      meta.setMaxLines(2);
      card.addView(meta);
      gap(card, 14);
      card.addView(text(format(job.created), 11, MUTED, false));
      card.setFocusable(true);
      card.setContentDescription(
          modeLabel(job.mode) + "任务，" + statusLabel(job.status) + "，" + job.title());
      card.setOnClickListener(
          v -> {
            selectedJob = job;
            page = "result";
            render();
          });
      gap(content, 14);
    }
  }

  void result() {
    PrismApp.Job job = selectedJob;
    if (job == null && !restoredJobId.isEmpty())
      for (PrismApp.Job saved : app.jobs)
        if (saved.id.equals(restoredJobId)) {
          job = saved;
          break;
        }
    if (job == null) {
      if (app.active != null) job = app.active;
      else if (!app.jobs.isEmpty()) job = app.jobs.get(0);
    }
    if (job == null) {
      history();
      return;
    }
    selectedJob = job;
    PrismApp.Job current = job;
    title("RESULT / " + modeLabel(job.mode), statusLabel(job.status), format(job.created));
    LinearLayout meta = card(content);
    eyebrow(meta, "REQUEST / 提交信息");
    TextView request = text(job.prompt, 15, INK, false);
    request.setTextIsSelectable(true);
    meta.addView(request);
    gap(meta, 18);
    info(meta, job.mode.equals("image") ? "选择的主模型" : "选择模型", job.model);
    info(meta, "思考等级", job.effort == null ? "服务默认（未传入）" : job.effort);
    info(meta, "任务 ID", job.id);
    String effective = job.result.optString("effectiveModel", "");
    if (!effective.isBlank()) info(meta, job.mode.equals("image") ? "实际执行主模型" : "实际使用模型", effective);
    String reasoning = job.result.optString("reasoningEffort", "");
    if (!reasoning.isBlank()) info(meta, "实际思考等级", reasoning);
    if (!effective.isBlank() && !effective.equals(job.model))
      hint(meta, "服务回退到兼容主模型；这不是底层绘图模型的版本。", CORAL);
    if (job.mode.equals("image")) { gap(meta, 10); info(meta, "绘图模型版本", "托管工具未披露"); }
    gap(content, 16);
    if (!Protocol.terminal(job.status)) {
      LinearLayout progress = card(content);
      LinearLayout row = horizontal();
      ProgressBar spinner = new ProgressBar(this);
      spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(MINT));
      row.addView(spinner, new LinearLayout.LayoutParams(dp(26), dp(26)));
      TextView t = text("   " + (job.status.equals("queued") ? "任务已排队" : "模型正在工作"), 16, INK, true);
      row.addView(t);
      progress.addView(row);
      gap(progress, 14);
      hint(progress, "自动轮询，不重复提交。切换页面仍可继续跟踪。", MUTED);
      if (!job.message.isBlank()) {
        gap(progress, 12);
        hint(progress, job.message, CORAL);
      }
      gap(progress, 18);
      LinearLayout controls = horizontal();
      controls.addView(
          button("重新查询", "refresh", false, () -> app.resume(current)),
          new LinearLayout.LayoutParams(0, -2, 1));
      LinearLayout cancel =
          button(
              "取消任务",
              "close",
              false,
              () ->
                  new AlertDialog.Builder(this)
                      .setTitle("取消此任务？")
                      .setMessage("会向服务发送取消请求，最终状态由服务返回。")
                      .setNegativeButton("继续等待", null)
                      .setPositiveButton("取消任务", (d, w) -> app.cancel(current))
                      .show());
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
      cp.leftMargin = dp(8);
      controls.addView(cancel, cp);
      progress.addView(controls);
      if (!job.message.isBlank()) {
        gap(progress, 10);
        progress.addView(
            button(
                "停止本机跟踪",
                "close",
                false,
                () ->
                    new AlertDialog.Builder(this)
                        .setTitle("停止本机跟踪？")
                        .setMessage("这不会取消服务端任务；可在任务记录中重新查询。")
                        .setNegativeButton("继续跟踪", null)
                        .setPositiveButton("停止跟踪", (d, w) -> app.stopTracking(current))
                        .show()));
      }
      gap(content, 16);
    } else {
      if (job.status.equals("failed") || job.status.equals("cancelled")) {
        LinearLayout fail = card(content);
        hint(
            fail,
            "服务返回 " + job.result.optString("errorCode", job.status) + "。原请求已保留，可调整后重新测试。",
            CORAL);
        gap(content, 16);
      }
      if (job.status.equals("needs_input")) {
        LinearLayout needs = card(content);
        hint(needs, job.message, MUTED);
        JSONArray questions = job.result.optJSONArray("questions");
        if (questions != null)
          for (int i = 0; i < questions.length(); i++) {
            gap(needs, 10);
            hint(needs, questions.optString(i), CORAL);
          }
        gap(content, 16);
      }
      String text = job.result.optString("text", "");
      if (!text.isBlank()) {
        LinearLayout output = card(content);
        eyebrow(output, "OUTPUT / 文本结果");
        TextView result = text("", 16, INK, false);
        result.setText(Visuals.markdown(text));
        result.setTextIsSelectable(true);
        result.setLineSpacing(dp(7), 1);
        output.addView(result);
        gap(output, 20);
        LinearLayout actions = horizontal();
        actions.addView(
            button(
                "复制",
                "copy",
                false,
                () -> {
                  android.content.ClipboardManager clip =
                      (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                  clip.setPrimaryClip(ClipData.newPlainText("Prism Task", text));
                  toast("结果已复制");
                }),
            new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1);
        sp.leftMargin = dp(8);
        actions.addView(
            button(
                "分享",
                "share",
                false,
                () ->
                    startActivity(
                        Intent.createChooser(
                            new Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, text),
                            "分享文字结果"))),
            sp);
        output.addView(actions);
        gap(content, 16);
      }
      for (File file : job.files) {
        LinearLayout imageCard = card(content);
        eyebrow(imageCard, "OUTPUT / 图片产物");
        ImageView image = imageView(file, 900);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageCard.addView(image, new LinearLayout.LayoutParams(-1, dp(280)));
        gap(imageCard, 16);
        LinearLayout actions = horizontal();
        actions.addView(
            button(
                "保存",
                "download",
                false,
                () -> {
                  exportFile = file;
                  exportImage.launch(file.getName());
                }),
            new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1);
        sp.leftMargin = dp(8);
        actions.addView(button("分享", "share", false, () -> shareFile(file)), sp);
        imageCard.addView(actions);
        gap(content, 16);
      }
      if (!job.message.isBlank()) {
        hint(content, job.message, CORAL);
        gap(content, 10);
      }
      JSONArray artifacts = job.result.optJSONArray("artifacts");
      if (artifacts != null
          && artifacts.length() > job.files.size()
          && job.status.equals("completed")) {
        content.addView(button("重新下载图片产物", "download", false, () -> app.download(current)));
        gap(content, 16);
      }
      JSONObject usage = job.result.optJSONObject("usage");
      if (usage != null) {
        LinearLayout u = card(content);
        eyebrow(u, "USAGE / TOKEN 用量");
        info(
            u,
            "输入 / 输出",
            usage.optLong("inputTokens", 0) + " / " + usage.optLong("outputTokens", 0));
        gap(content, 16);
      }
      content.addView(
          button(
              "使用这个提示词继续创作",
              "pen",
              true,
              () -> {
                app.prompt = current.prompt;
                app.mode = current.mode;
                navigate("studio");
              }),
          new LinearLayout.LayoutParams(-1, dp(54)));
    }
  }

  ImageView imageView(File file, int target) {
    ImageView view = new ImageView(this);
    view.setBackground(shape(BG, 14, false));
    view.setClipToOutline(true);
    view.setScaleType(ImageView.ScaleType.CENTER_CROP);
    view.setContentDescription("图片预览");
    String key = file.getAbsolutePath() + "#" + target;
    Bitmap bitmap = bitmaps.get(key);
    if (bitmap != null) view.setImageBitmap(bitmap);
    else
      app.io.execute(
          () -> {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            int sample = 1;
            while (Math.max(options.outWidth, options.outHeight) / sample > target * 2) sample *= 2;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            try {
              Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
              if (decoded != null)
                runOnUiThread(
                    () -> {
                      if (!isDestroyed()) {
                        bitmaps.put(key, decoded);
                        view.setImageBitmap(decoded);
                      }
                    });
            } catch (OutOfMemoryError ignored) {
              runOnUiThread(() -> toast("图片较大，请保存后查看原图"));
            }
          });
    return view;
  }

  void shareFile(File file) {
    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
    String mime = getContentResolver().getType(uri);
    Intent intent =
        new Intent(Intent.ACTION_SEND)
            .setType(mime == null ? "image/png" : mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    intent.setClipData(ClipData.newUri(getContentResolver(), "Prism image", uri));
    startActivity(Intent.createChooser(intent, "分享图片"));
  }

  static String modeLabel(String mode) {
    return switch (mode) {
      case "image" -> "生图";
      case "vision" -> "识图";
      default -> "生文";
    };
  }

  static String statusLabel(String status) {
    return switch (status) {
      case "submitting" -> "正在提交";
      case "queued" -> "已排队";
      case "running" -> "进行中";
      case "completed" -> "已完成";
      case "failed" -> "执行失败";
      case "cancelled" -> "已取消";
      case "needs_input" -> "需要补充输入";
      default -> status;
    };
  }

  String format(long time) {
    return new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(new Date(time));
  }

  String formatIso(String raw) {
    try {
      return format(java.time.Instant.parse(raw).toEpochMilli());
    } catch (Exception e) {
      return "时间格式未知";
    }
  }

  void hideKeyboard() {
    View v = getCurrentFocus();
    if (v != null)
      ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(v.getWindowToken(), 0);
  }

  void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }
}
