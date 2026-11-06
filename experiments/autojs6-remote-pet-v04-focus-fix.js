// 糯米机桌宠 V0.4 · 修复键盘焦点被悬浮窗占用
// 只更新启动脚本，不修改糯米机，不改变桌宠现有 HTML、动画或聊天逻辑。
// 需要 AutoJs6 6.8+；仅申请「显示在其他应用上层」权限。
// 停止旧 V0.3，再运行本脚本。关闭：AutoJs6「任务」> 停止脚本。

if (!floaty.hasPermission()) {
  floaty.requestPermission();
  exit();
}

var url = "https://raw.githubusercontent.com/yanlue1211/TapNest/main/experiments/pet-web/index.html";
var html;
try {
  var res = http.get(url, {timeout: 15000});
  if (res.statusCode !== 200) throw Error("HTTP " + res.statusCode);
  html = res.body.string();
  if (!html.includes('id="pet"') || !html.includes("window.PetPreview"))
    throw Error("桌宠 HTML 校验失败");
} catch (e) {
  toastLog("下载桌宠网页失败：" + e);
  exit();
}

var density = context.getResources().getDisplayMetrics().density;
function dp(n) { return Math.round(n * density); }
var winWidth = dp(220), winHeight = dp(220);
var win = floaty.rawWindow(
  <frame w="220" h="220" bg="#00000000">
    <webview id="pet" w="*" h="*" />
  </frame>
);
win.setSize(winWidth, winHeight);
win.setPosition(
  Math.max(0, device.width - winWidth - dp(16)),
  Math.max(0, Math.round(device.height * 0.38))
);
win.setTouchable(true);

// rawWindow 默认不抢键盘焦点：只有长按输入时才临时取得焦点。
var focusHeld = false;
var chatOpen = false;
var imeWasVisible = false;
var imeHiddenChecks = 0;
var focusAt = 0;
var checking = false;
var checkStartedAt = 0;
var shuttingDown = false;
var wm = null;

function takeFocus() {
  if (shuttingDown || focusHeld) return;
  focusHeld = true;
  focusAt = Date.now();
  imeWasVisible = false;
  imeHiddenChecks = 0;
  try {
    win.requestFocus();
    ui.run(function () { win.pet.requestFocus(); });
  } catch (e) {
    focusHeld = false;
    toast("桌宠输入焦点异常：" + e);
  }
}

function releaseFocus() {
  if (!focusHeld || shuttingDown) return;
  focusHeld = false;
  imeWasVisible = false;
  imeHiddenChecks = 0;
  // 先释放系统焦点，再清理输入控件，防止后台 ChatGPT 输入框失焦。
  ui.run(function () {
    try { win.disableFocus(); } catch (_) {}
    try { win.pet.clearFocus(); } catch (_) {}
    try {
      var imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
      imm.hideSoftInputFromWindow(win.pet.getWindowToken(), 0);
    } catch (_) {}
  });
}

ui.run(function () {
  var view = win.pet;
  view.setBackgroundColor(android.graphics.Color.TRANSPARENT);
  var settings = view.getSettings();
  settings.setJavaScriptEnabled(true);
  settings.setDomStorageEnabled(false);
  settings.setAllowFileAccess(false);
  settings.setAllowContentAccess(false);
  view.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null);
});

// 监测网页里 #chat.open 的开合：发送或取消时立即还给其他 App 键盘焦点。
// 同时尽可能检测 Android 11+ 的系统键盘收起（按返回键的情况）。
// 如果某机型不向悬浮 WebView 上报 IME insets，就以聊天框关闭为准。
var resultCallback = new JavaAdapter(android.webkit.ValueCallback, {
  onReceiveValue: function (raw) {
    checking = false;
    if (shuttingDown) return;
    var opened = String(raw) === "true";
    chatOpen = opened;
    if (!focusHeld) return;
    if (!opened) {
      releaseFocus();
      return;
    }
    // Android 11+：只有真的观察到键盘出现，才用其消失判定收键盘。
    try {
      if (android.os.Build.VERSION.SDK_INT >= 30) {
        var insets = win.pet.getRootWindowInsets();
        if (insets) {
          var visible = insets.isVisible(android.view.WindowInsets.Type.ime());
          if (visible) {
            imeWasVisible = true;
            imeHiddenChecks = 0;
          } else if (imeWasVisible && Date.now() - focusAt > 900) {
            imeHiddenChecks++;
            if (imeHiddenChecks >= 3) releaseFocus();
          }
        }
      }
    } catch (_) {}
  }
});

var touchX=0,touchY=0,windowX=0,windowY=0,touchAt=0,dragging=false;
win.pet.setOnTouchListener(function(v,e) {
  var action=e.getActionMasked();
  if (action === 0) {
    touchX=e.getRawX(); touchY=e.getRawY();
    windowX=win.getX(); windowY=win.getY();
    touchAt=Date.now(); dragging=false;
    // 键盘被系统返回键收起后，点击聊天框可再次获取输入焦点。
    if (chatOpen && !focusHeld) takeFocus();
  } else if (action === 2) {
    var dx=e.getRawX()-touchX, dy=e.getRawY()-touchY;
    if (!chatOpen && Math.abs(dx)+Math.abs(dy)>dp(12)) dragging=true;
    if (dragging) {
      win.setPosition(
        Math.max(0, Math.min(device.width-winWidth, Math.round(windowX+dx))),
        Math.max(0, Math.min(device.height-winHeight, Math.round(windowY+dy)))
      );
    }
  } else if (action === 1 && !dragging && Date.now()-touchAt>=700) {
    takeFocus();
    ui.run(function () {
      try {
        win.pet.evaluateJavascript(
          "window.PetPreview&&window.PetPreview.openChat()", null
        );
      } catch (_) {}
    });
  }
  return false; // 不拦截网页本身的点击/长按
});

setInterval(function () {
  if (shuttingDown || (!focusHeld && !chatOpen)) return;
  if (checking && Date.now() - checkStartedAt < 2000) return;
  checking = true;
  checkStartedAt = Date.now();
  ui.run(function () {
    try {
      win.pet.evaluateJavascript(
        "(function(){var e=document.getElementById('chat');return !!(e&&e.classList.contains('open'))})()",
        resultCallback
      );
    } catch (_) {
      checking = false;
      // 不支持查询时安全兜底：一分钟自动释放避免永久锁住别的输入框。
      if (focusHeld && Date.now()-focusAt>60000) releaseFocus();
    }
  });
}, 250);

events.on("exit", function () {
  shuttingDown = true;
  try { win.disableFocus(); } catch (_) {}
  try { win.close(); } catch (_) {}
});

toast("🐶 桌宠 V0.4 已启动：修复关闭聊天后键盘焦点残留");
setInterval(function () {}, 1000);
