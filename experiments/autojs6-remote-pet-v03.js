// 糯米机桌宠 · 远程网页 V0.3 (AutoJs6 6.8+)
// 从用户自己的 GitHub 源码读取 HTML，无需 GitHack，也不安装其他插件。
// 无障碍 / 截屏 / AI 密钥均不需要；关闭请在 AutoJs6「任务」页停止脚本。
if (!floaty.hasPermission()) { floaty.requestPermission(); exit(); }

var url = "https://raw.githubusercontent.com/yanlue1211/TapNest/main/experiments/pet-web/index.html";
var html = "";
try {
  var response = http.get(url, { timeout: 15000 });
  if (response.statusCode !== 200) throw Error("HTTP " + response.statusCode);
  html = response.body.string();
  if (!html.includes('id="pet"') || !html.includes("window.PetPreview"))
    throw Error("返回内容不是桌宠网页");
} catch (e) {
  toastLog("桌宠网页下载失败：" + e + "\n请检查网络或 VPN，然后重试");
  exit();
}

var d = context.getResources().getDisplayMetrics().density;
var W = Math.round(220 * d), H = Math.round(220 * d);
var win = floaty.rawWindow(
  <frame w="220" h="220" bg="#00000000">
    <webview id="pet" w="*" h="*" />
  </frame>
);
win.setSize(W, H);
win.setPosition(Math.max(0, device.width - W - Math.round(16 * d)),
                Math.max(0, Math.round(device.height * 0.38)));
win.setTouchable(true);

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

var sx=0,sy=0,ox=0,oy=0,at=0,moved=false;
win.pet.setOnTouchListener(function (v,e) {
  var act=e.getActionMasked();
  if (act === 0) {
    sx=e.getRawX(); sy=e.getRawY(); ox=win.getX(); oy=win.getY();
    at=Date.now(); moved=false;
  } else if (act === 2) {
    var dx=e.getRawX()-sx, dy=e.getRawY()-sy;
    if (Math.abs(dx)+Math.abs(dy)>Math.round(12*d)) moved=true;
    if (moved) win.setPosition(
      Math.max(0, Math.min(device.width-W, Math.round(ox+dx))),
      Math.max(0, Math.min(device.height-H, Math.round(oy+dy)))
    );
  } else if (act === 1 && !moved && Date.now()-at>=700) {
    try {
      win.requestFocus();
      ui.run(function () {
        win.pet.requestFocus();
        win.pet.evaluateJavascript("window.PetPreview&&window.PetPreview.openChat()",null);
      });
    } catch (e) { toast("长按输入框尚需适配："+e); }
  }
  return false; // 让网页继续接收点击和长按事件
});

toast("🐶 网页桌宠已启动！试试单击、拖动、长按");
setInterval(function(){},1000);
