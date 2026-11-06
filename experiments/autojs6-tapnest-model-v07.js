// 🐾 TapNest 网页模型引擎 V0.7 · AutoJs6 6.8+
// 目标：加载 TapNest 原版导出的「单只桌宠 ZIP」，保留 PNG 图层与 rig.json。
// 无需新增 APK、插件、无障碍、截图、Root、AI 密钥。
// ZIP 只在本机解包到内存，不上传；HTML 由本人的 GitHub 源码提供。
// 注意：本版为实验性移植，复杂模型的表现需要手机实际检查。
// 退出方法：AutoJs6「任务」里停止此脚本。

if (!floaty.hasPermission()) {
  floaty.requestPermission();
  exit();
}
var TAG="TapNest V0.7";
var rawRoot="https://raw.githubusercontent.com/yanlue1211/TapNest/main/experiments/pet-web/";
var rigPage=rawRoot+"rig-viewer.html", demoPage=rawRoot+"index.html";
var MAX_ZIP=60*1024*1024,MAX_JSON=1024*1024,MAX_PNG=6*1024*1024,MAX_TOTAL=26*1024*1024;
var chosen=null,pack=null,viewUrl=demoPage,webHtml="";

// 自己选文件：可逐级打开任何可读的公共文件夹，不再限制「最近 40 个 ZIP」。
// Android 文件夹不是网页：这里用 AutoJs6 原生选择对话框逐级浏览。
// 选择实际 ZIP 文件时，后面的解析及透明桌宠逻辑保持完全不变。
var ROOT="/storage/emulated/0";
function canonical(path) {
  return String(new java.io.File(path).getCanonicalPath());
}
function inSharedStorage(path) {
  var p=canonical(path);
  return p===ROOT||p.indexOf(ROOT+"/")===0;
}
function isZipFile(f) {
  return f&&f.isFile()&&/\.zip$/i.test(String(f.getName()));
}
function pickManually() {
  var path=dialogs.rawInput("输入 ZIP 的完整本地路径",ROOT+"/Download/");
  if(!path)return null;
  path=String(path).trim().replace(/^["']|["']$/g,"");
  if(!inSharedStorage(path))throw Error("请只选择手机内部存储下的文件");
  var file=new java.io.File(path);
  if(!isZipFile(file))throw Error("文件不存在或不是 ZIP："+path);
  if(file.length()>MAX_ZIP)throw Error("ZIP 超过 60 MB");
  return path;
}
function showPlaces() {
  var places=[
    ["📥 Download / 下载",ROOT+"/Download"],
    ["📁 Downloads",ROOT+"/Downloads"],
    ["📄 Documents / 文档",ROOT+"/Documents"],
    ["📂 内部存储 · 自己找文件",ROOT],
    ["⌨️ 粘贴文件完整路径",null],
    ["🐶 演示小狗（不使用 ZIP）","demo"]
  ];
  while(true) {
    var choice=dialogs.select("🐶 V0.6 · 选择模型 ZIP 的位置",places.map(p=>p[0]));
    if(choice<0)return null;
    var dest=places[choice][1];
    if(dest==="demo")return "demo";
    if(dest===null)return pickManually();
    var f=new java.io.File(dest);
    if(f.exists()&&f.isDirectory()&&f.listFiles()!==null)return canonical(dest);
    toast("该文件夹不存在或不能访问，换个位置试试");
  }
}
function chooseZip() {
  var where=showPlaces();
  if(!where)return null;
  if(where==="demo")return "demo";
  var folder=where, page=0;
  const PAGE=22;
  while(true) {
    var dir=new java.io.File(folder),all=dir.listFiles();
    if(all===null) {
      var retry=dialogs.select("无法读取："+String(dir.getName()),["⬅ 返回位置选择","⌨ 输入完整路径","取消"]);
      if(retry===0){where=showPlaces();if(!where||where==="demo")return where;folder=where;page=0;continue;}
      if(retry===1)return pickManually();
      return null;
    }
    var files=Array.prototype.slice.call(all).filter(f=>f.isDirectory()||isZipFile(f));
    files.sort(function(a,b) {
      if(a.isDirectory()!==b.isDirectory())return a.isDirectory()?-1:1;
      return String(a.getName()).localeCompare(String(b.getName()),"zh-CN");
    });
    var pages=Math.max(1,Math.ceil(files.length/PAGE));
    page=Math.min(page,pages-1);
    var items=[],actions=[];
    function add(label,action){items.push(label);actions.push(action);}
    add("📍 切换文件夹位置",{type:"places"});
    if(folder!==ROOT)add("⬆️ 返回上一级",{type:"up"});
    add("⌨️ 输入文件完整路径",{type:"manual"});
    var start=page*PAGE;
    files.slice(start,start+PAGE).forEach(function(f){
      var isDir=f.isDirectory();
      add((isDir?"📁 ":"📦 ")+String(f.getName()),{type:isDir?"folder":"zip",path:canonical(f.getAbsolutePath())});
    });
    if(page>0)add("◀ 上一页",{type:"prev"});
    if(page+1<pages)add("下一页 ▶",{type:"next"});
    var title="选择模型 ZIP｜"+String(dir.getName())+"｜"+(page+1)+"/"+pages+" 页";
    var choice=dialogs.select(title,items);
    if(choice<0)return null;
    var a=actions[choice];
    if(a.type==="places"){where=showPlaces();if(!where||where==="demo")return where;folder=where;page=0;}
    else if(a.type==="up"){folder=canonical(dir.getParent());if(!inSharedStorage(folder))folder=ROOT;page=0;}
    else if(a.type==="manual")return pickManually();
    else if(a.type==="prev")page--;
    else if(a.type==="next")page++;
    else if(a.type==="folder"){folder=a.path;page=0;}
    else if(a.type==="zip"){
      var file=new java.io.File(a.path);
      if(!inSharedStorage(a.path)||!isZipFile(file))throw Error("所选文件不存在或不是 ZIP");
      if(file.length()>MAX_ZIP)throw Error("ZIP 超过 60 MB");
      return a.path;
    }
  }
}

function readBounded(stream,maximum) {
  var out=new java.io.ByteArrayOutputStream();
  var buffer=java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE,8192);
  var total=0,n;
  try{
    while((n=stream.read(buffer))!==-1){
      total+=n;
      if(total>maximum)throw Error("单文件超出大小限制："+maximum+" 字节");
      out.write(buffer,0,n);
    }
    return out.toByteArray();
  }finally{out.close()}
}
function readEntry(zip,entry,maximum) {
  var input=zip.getInputStream(entry);
  try{return readBounded(input,maximum)}finally{input.close()}
}
function safeImage(name){
  return typeof name==="string"&&/^[a-zA-Z0-9_-]+\.png$/i.test(name);
}
function readPetZip(filepath){
  var zip=new java.util.zip.ZipFile(new java.io.File(filepath));
  try{
    var entries=zip.entries(),rigPaths=[];
    while(entries.hasMoreElements()){
      var item=entries.nextElement(),name=String(item.getName());
      if(name==="rig.json"||/^(?:[A-Za-z0-9_-]+\/)*rig\.json$/.test(name))rigPaths.push(name);
    }
    if(!rigPaths.length)throw Error("ZIP 中没有 rig.json：请导出 TapNest 多图层桌宠（非 GIF）。");
    // Single-pet export normally has rig.json at root. Backups also work when
    // they contain one pet; multi-pet backups currently select first layered pet.
    rigPaths.sort();
    var rigEntryName=rigPaths[0],rigEntry=zip.getEntry(rigEntryName);
    var rig=JSON.parse(String(new java.lang.String(readEntry(zip,rigEntry,MAX_JSON),"UTF-8")));
    if(!rig||rig.canvasWidth<1||rig.canvasWidth>4096||rig.canvasHeight<1||rig.canvasHeight>4096||
       !Array.isArray(rig.layers)||rig.layers.length<1||rig.layers.length>48)
      throw Error("rig.json 内容或图层数量不合法");
    var prefix=rigEntryName.substring(0,rigEntryName.length-"rig.json".length);
    var assets={},dimensions={},total=0,loaded=0;
    for(var i=0;i<rig.layers.length;i++){
      var layer=rig.layers[i],name=layer.image;
      if(!name)continue;
      if(!safeImage(name))throw Error("模型存在非法 PNG 路径："+name);
      if(assets[name])continue;
      var entry=zip.getEntry(prefix+name);
      if(!entry)throw Error("缺少图片："+prefix+name);
      var arr=readEntry(zip,entry,MAX_PNG);
      // Verify PNG magic. Do not decode or execute arbitrary ZIP contents.
      if(arr.length<24 || (arr[0]&255)!==137 || (arr[1]&255)!==80 ||
         (arr[2]&255)!==78 || (arr[3]&255)!==71)
        throw Error(name+" 不是有效 PNG 文件");
      // Preserve original coordinates while downsampling for a small floating window.
      // Mirroring/pivots/bone coordinates stay based on the full PNG dimensions.
      var bounds=new android.graphics.BitmapFactory.Options();
      bounds.inJustDecodeBounds=true;
      android.graphics.BitmapFactory.decodeByteArray(arr,0,arr.length,bounds);
      var ow=bounds.outWidth,oh=bounds.outHeight;
      if(ow<1||oh<1||ow>4096||oh>4096||ow*oh>16000000)
        throw Error("图片尺寸无效或过大："+name);
      dimensions[name]=[ow,oh];
      var sample=1;
      while(Math.max(ow,oh)/sample>768)sample*=2;
      if(sample>1){
        var opts=new android.graphics.BitmapFactory.Options();
        opts.inSampleSize=sample;
        opts.inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888;
        var bmp=android.graphics.BitmapFactory.decodeByteArray(arr,0,arr.length,opts);
        if(!bmp)throw Error("PNG 解码失败："+name);
        var output=new java.io.ByteArrayOutputStream();
        try{
          if(!bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,95,output))
            throw Error("缩放图层失败："+name);
          arr=output.toByteArray();
        }finally{output.close();bmp.recycle();}
      }
      total+=arr.length;
      if(total>MAX_TOTAL)throw Error("模型图片总大小超过 26 MB，暂时无法加载");
      assets[name]="data:image/png;base64,"+String(android.util.Base64.encodeToString(arr,android.util.Base64.NO_WRAP));
      loaded++;
    }
    if(!loaded)throw Error("rig.json 没有关联任何 PNG 图片");
    return {rig:rig,assets:assets,dimensions:dimensions};
  }finally{zip.close()}
}

try{
  var selectedPath=chooseZip();
  if(!selectedPath)exit();
  if(selectedPath!=="demo"){
    chosen={path:selectedPath,name:String(new java.io.File(selectedPath).getName())};
    pack=readPetZip(chosen.path);
    viewUrl=rigPage;
  }
}catch(e){
  toastLog("模型导入失败："+e+"\n请重新选择 TapNest 导出的 ZIP（不要解压）。");
  exit();
}

// 选择桌宠的实际显示尺寸；上方气泡与输入框单独预留空间。
var SIZE_OPTIONS=[100,120,140,160,180,220];
var PET_SIZE_DP=140;
try{
  var prefStore=storages.create("tapnest_web_pet_preferences");
  var savedSize=Number(prefStore.get("petSizeDp",140));
  var defaultIndex=SIZE_OPTIONS.indexOf(savedSize);
  var menu=SIZE_OPTIONS.map(function(n){
    return (n===savedSize?"✅ ":"") + n + " dp" +
      (n<=120?"（小巧）":n<=160?"（推荐）":"（较大）");
  });
  var sizeIndex=dialogs.select("🐶 桌宠大小 · 选择一个尺寸",menu);
  if(sizeIndex<0)exit();
  PET_SIZE_DP=SIZE_OPTIONS[sizeIndex];
  prefStore.put("petSizeDp",PET_SIZE_DP);
}catch(e){
  // 设置存储不可用时仍然可以启动，默认显示为 140dp。
  if(PET_SIZE_DP!==140)toast("尺寸设置保存失败，本次仍按所选大小显示");
}
try{
  var response=http.get(viewUrl,{timeout:20000});
  if(response.statusCode!==200)throw Error("网页 HTTP "+response.statusCode);
  webHtml=response.body.string();
  if(webHtml.indexOf("window.PetPreview")<0)throw Error("网页格式不对");
  if(pack){
    // 给桌宠图像固定实际大小；聊天扩展窗口不会连带放大角色。
    webHtml=webHtml.replace("</head>","<style>:root{--pet-size:"+PET_SIZE_DP+"px}</style></head>");
    // Inline data URL resources: after this step, the WebView does not need any
    // cross-origin image requests. Prevent HTML/script delimiter injection.
    var json=JSON.stringify(pack).replace(/</g,"\\u003c");
    webHtml=webHtml.replace("</head>","<script>window.TAPNEST_PACK="+json+";<\/script></head>");
  }
}catch(e){
  toastLog("网页引擎加载失败："+e+"\n请检查 GitHub 访问后重试。");
  exit();
}

var d=context.getResources().getDisplayMetrics().density;
function dp(v){return Math.round(v*d)}
var WW=dp(Math.max(PET_SIZE_DP,176)),HH=dp(PET_SIZE_DP+70);
var chatExtra=dp(46),expanded=false,collapsedY=0;
var win=floaty.rawWindow(
  <frame w="220" h="220" bg="#00000000">
    <webview id="pet" w="*" h="*" />
  </frame>
);
win.setSize(WW,HH);
win.setPosition(
  Math.max(0,device.width-WW-dp(16)),
  Math.max(0,Math.round(device.height*.38))
);
win.setTouchable(true);

// 长按展开上方的输入卡片，小狗仍在下方原位置，不会被遮住。
// 窗口增加 46dp，宠物本身的绘制区域不变。
function updateChatWindow(open){
  if(open===expanded)return;
  try{
    if(open){
      collapsedY=win.getY();
      expanded=true;
      win.setSize(WW,HH+chatExtra);
      win.setPosition(win.getX(),Math.max(0,collapsedY-chatExtra));
    }else{
      expanded=false;
      win.setSize(WW,HH);
      win.setPosition(win.getX(),Math.max(0,collapsedY));
    }
  }catch(e){toast("桌宠输入框尺寸调整失败："+e)}
}

// Preserve the previously verified V0.4 keyboard fix: the floaty window must
// release input focus after sending/cancelling, hiding IME, or stopping the script.
var focusHeld=false,chatOpen=false,imeWasVisible=false,hiddenChecks=0,focusAt=0;
var checking=false,checkStartedAt=0,shuttingDown=false;
function takeFocus(){
  if(shuttingDown||focusHeld)return;
  updateChatWindow(true);
  focusHeld=true;focusAt=Date.now();imeWasVisible=false;hiddenChecks=0;
  try{
    win.requestFocus();
    ui.run(function(){win.pet.requestFocus()});
  }catch(e){focusHeld=false;toast("获取输入焦点失败："+e)}
}
function releaseFocus(){
  if(!focusHeld||shuttingDown)return;
  focusHeld=false;imeWasVisible=false;hiddenChecks=0;
  ui.run(function(){
    try{win.disableFocus()}catch(_){}
    try{win.pet.clearFocus()}catch(_){}
    try{
      var ime=context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
      ime.hideSoftInputFromWindow(win.pet.getWindowToken(),0);
    }catch(_){}
  });
}
ui.run(function(){
  var view=win.pet;
  view.setBackgroundColor(android.graphics.Color.TRANSPARENT);
  var settings=view.getSettings();
  settings.setJavaScriptEnabled(true);
  settings.setDomStorageEnabled(false);
  settings.setAllowFileAccess(false);
  settings.setAllowContentAccess(false);
  view.loadDataWithBaseURL(viewUrl,webHtml,"text/html","UTF-8",null);
});
var cb=new JavaAdapter(android.webkit.ValueCallback,{
  onReceiveValue:function(raw){
    checking=false;
    if(shuttingDown)return;
    var opened=String(raw)==="true";
    chatOpen=opened;
    updateChatWindow(opened);
    if(!focusHeld)return;
    if(!opened){releaseFocus();return}
    try{
      if(android.os.Build.VERSION.SDK_INT>=30){
        var insets=win.pet.getRootWindowInsets();
        if(insets){
          if(insets.isVisible(android.view.WindowInsets.Type.ime())){
            imeWasVisible=true;hiddenChecks=0;
          }else if(imeWasVisible&&Date.now()-focusAt>900){
            hiddenChecks++;
            if(hiddenChecks>=3)releaseFocus();
          }
        }
      }
    }catch(_){}
  }
});
var sx=0,sy=0,ox=0,oy=0,at=0,drag=false;
win.pet.setOnTouchListener(function(v,e){
  var a=e.getActionMasked();
  if(a===0){
    sx=e.getRawX();sy=e.getRawY();ox=win.getX();oy=win.getY();
    at=Date.now();drag=false;
    if(chatOpen&&!focusHeld)takeFocus();
  }else if(a===2){
    var dx=e.getRawX()-sx,dy=e.getRawY()-sy;
    if(!chatOpen&&Math.abs(dx)+Math.abs(dy)>dp(12))drag=true;
    if(drag)win.setPosition(
      Math.max(0,Math.min(device.width-WW,Math.round(ox+dx))),
      Math.max(0,Math.min(device.height-HH,Math.round(oy+dy)))
    );
  }else if(a===1&&!drag&&Date.now()-at>=700&&!chatOpen){
    takeFocus();
    ui.run(function(){
      try{win.pet.evaluateJavascript("window.PetPreview&&window.PetPreview.openChat()",null)}catch(_){}
    });
  }
  return false;
});
setInterval(function(){
  if(shuttingDown||(!focusHeld&&!chatOpen))return;
  if(checking&&Date.now()-checkStartedAt<2000)return;
  checking=true;checkStartedAt=Date.now();
  ui.run(function(){
    try{
      win.pet.evaluateJavascript(
        "(function(){var e=document.getElementById('chat');return !!(e&&e.classList.contains('open'))})()",cb
      );
    }catch(_){
      checking=false;
      if(focusHeld&&Date.now()-focusAt>60000)releaseFocus();
    }
  });
},250);
events.on("exit",function(){
  shuttingDown=true;
  try{win.disableFocus()}catch(_){}
  try{win.close()}catch(_){}
});
toast(pack?"🐶 TapNest V0.7 已载入："+chosen.name+"，桌宠大小 "+PET_SIZE_DP+"dp":"🐶 演示小狗启动，桌宠大小 "+PET_SIZE_DP+"dp");
setInterval(function(){},1000);
