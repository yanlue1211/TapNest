// AutoJs6 / AutoX.js desktop floating ball - display-only experiment.
// No accessibility automation, screen reading, network requests or API keys.
// Run in the AutoJs6 script editor. Stop script from its running-task panel.
//
// Only the 104dp floating window intercepts touches; the rest of the
// screen is left to whatever app is underneath.
// First grant "Display over other apps" permission to AutoJs6.

if (!floaty.checkPermission()) {
    toast("请先给 AutoJs6 开启「显示在其他应用上层」权限，然后重新运行脚本");
    floaty.requestPermission();
    exit();
}

var density = context.getResources().getDisplayMetrics().density;
function dp(n) { return Math.round(n * density); }
var win = floaty.rawWindow(
    <frame w="104" h="104" gravity="center" bg="#00000000">
        <text id="orb" w="94" h="94" text="戳我"
              textSize="18sp" textColor="#116b53"
              gravity="center" />
    </frame>
);

win.setSize(dp(104), dp(104));
win.setPosition(Math.max(0, device.width - dp(140)), Math.floor(device.height * 0.39));
win.setTouchable(true);

ui.run(function () {
    var gd = new android.graphics.drawable.GradientDrawable();
    gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
    gd.setColor(android.graphics.Color.parseColor("#70D9B7"));
    gd.setStroke(dp(3), android.graphics.Color.parseColor("#DAFFF0"));
    win.orb.setBackground(gd);
});

var startRawX = 0, startRawY = 0, startWindowX = 0, startWindowY = 0;
var pressTime = 0, moving = false, count = 0;

win.orb.setOnTouchListener(function (view, event) {
    var action = event.getActionMasked();
    if (action === android.view.MotionEvent.ACTION_DOWN) {
        startRawX = event.getRawX();
        startRawY = event.getRawY();
        startWindowX = win.getX();
        startWindowY = win.getY();
        pressTime = new Date().getTime();
        moving = false;
        return true;
    }
    if (action === android.view.MotionEvent.ACTION_MOVE) {
        var dx = event.getRawX() - startRawX;
        var dy = event.getRawY() - startRawY;
        if (Math.abs(dx) + Math.abs(dy) > dp(12)) moving = true;
        if (moving) {
            win.setPosition(
                Math.max(0, Math.min(device.width - dp(104), Math.round(startWindowX + dx))),
                Math.max(0, Math.min(device.height - dp(140), Math.round(startWindowY + dy)))
            );
        }
        return true;
    }
    if (action === android.view.MotionEvent.ACTION_UP) {
        if (!moving) {
            if (new Date().getTime() - pressTime >= 650) {
                win.orb.setText("长按♡");
                toast("长按成功：之后可以换成聊天输入框");
            } else {
                count += 1;
                win.orb.setText(count % 2 === 0 ? "戳我" : "嘿嘿");
                toast("小圆球被戳了一下");
            }
        }
        return true;
    }
    if (action === android.view.MotionEvent.ACTION_CANCEL) return true;
    return true;
});

toast("透明小圆球已启动：按主页键查看；拖动可移动，长按可测试");
// Keep the script alive. Stop it from AutoJs6's running tasks to remove ball.
setInterval(function () {}, 1000);
