// AutoJs6 6.8.x: transparent WebView inside a system overlay.
// Local HTML only. No API key, accessibility service, screenshots, or plugins.
// Stop from AutoJs6 > Tasks (running scripts).
if (!floaty.hasPermission()) {
  floaty.requestPermission();
  exit();
}

var w = floaty.rawWindow(
  <frame w="124" h="124" bg="#00000000">
    <webview id="pet" w="*" h="*" />
  </frame>
);
w.setPosition(Math.max(0, device.width - 350), Math.floor(device.height * 0.35));
w.setTouchable(true);

var html = '<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">' +
'<style>*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;background:transparent!important;overflow:hidden}' +
'body{display:grid;place-items:center;touch-action:none;font-family:sans-serif}' +
'#b{width:92%;aspect-ratio:1;border:3px solid #effff4;border-radius:50%;' +
'display:grid;place-items:center;color:#136d5d;font-size:18px;font-weight:bold;user-select:none;' +
'background:radial-gradient(circle at 30% 25%,#dcfff1,#85e6c9 50%,#3ab28f);' +
'box-shadow:0 6px 15px #1eae8259;transition:transform .13s}' +
'#b:active{transform:scale(.93)}</style></head>' +
'<body><div id="b" onclick="this.textContent=this.textContent===\'戳我\'?\'嘿嘿\':\'戳我\'">戳我</div></body></html>';

ui.run(function () {
  w.pet.setBackgroundColor(android.graphics.Color.TRANSPARENT);
  w.pet.getSettings().setJavaScriptEnabled(true);
  w.pet.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
});
toast("HTML 悬浮小球已启动；按主页键查看，戳小球会变文字");
setInterval(function () {}, 1000);
