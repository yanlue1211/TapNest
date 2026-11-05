// TapNest x SullyOS remote web-pet proof V0.2.
// Requires AutoJs6 6.8+, "Display over other apps" permission.
// No plug-ins, accessibility, screen capture, API credentials or JS bridge.
// Stop from AutoJs6 -> Tasks when finished.
if (!floaty.hasPermission()) {
  floaty.requestPermission();
  exit();
}

var density = context.getResources().getDisplayMetrics().density;
function dp(n) { return Math.round(n * density); }
var WINDOW_W = dp(220), WINDOW_H = dp(220);
var page = "https://raw.githack.com/yanlue1211/TapNest/main/experiments/pet-web/index.html?v=02";
var win = floaty.rawWindow(
  <frame w="220" h="220" bg="#00000000">
    <webview id="pet" w="*" h="*" />
  </frame>
);
win.setSize(WINDOW_W, WINDOW_H);
win.setPosition(
  Math.max(0, device.width - WINDOW_W - dp(16)),
  Math.max(0, Math.round(device.height * 0.38))
);
win.setTouchable(true);

ui.run(function() {
  var v = win.pet;
  v.setBackgroundColor(android.graphics.Color.TRANSPARENT);
  var settings = v.getSettings();
  settings.setJavaScriptEnabled(true);
  settings.setDomStorageEnabled(true);
  settings.setAllowFileAccess(false);
  settings.setAllowContentAccess(false);
  v.loadUrl(page);
});

// Drag the entire small overlay without swallowing WebView click / long-press.
// WebView handles the actual HTML tap, blink, bubbles and draft input.
var sx = 0, sy = 0, ox = 0, oy = 0, startedAt = 0, moved = false;
win.pet.setOnTouchListener(function(view, e) {
  var kind = e.getActionMasked();
  if (kind === android.view.MotionEvent.ACTION_DOWN) {
    sx = e.getRawX(); sy = e.getRawY();
    ox = win.getX(); oy = win.getY();
    startedAt = new Date().getTime(); moved = false;
  } else if (kind === android.view.MotionEvent.ACTION_MOVE) {
    var dx = e.getRawX() - sx, dy = e.getRawY() - sy;
    if (Math.abs(dx) + Math.abs(dy) > dp(12)) moved = true;
    if (moved) {
      win.setPosition(
        Math.max(0, Math.min(device.width - WINDOW_W, Math.round(ox + dx))),
        Math.max(0, Math.min(device.height - WINDOW_H, Math.round(oy + dy)))
      );
    }
  } else if (kind === android.view.MotionEvent.ACTION_UP) {
    if (!moved && new Date().getTime() - startedAt >= 700) {
      // Floaty raw windows are not normally focusable: allow input after long press.
      try {
        win.requestFocus();
        ui.run(function() {
          win.pet.requestFocus();
          win.pet.evaluateJavascript(
            "window.PetPreview && window.PetPreview.openChat()", null
          );
        });
      } catch (err) {
        toast("输入框焦点暂不可用：" + err);
      }
    }
  }
  return false;
});

toast("🐶 正在加载远程网页桌宠；返回桌面点击试试");
setInterval(function() {}, 1000);
