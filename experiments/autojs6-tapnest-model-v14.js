// 🐾 TapNest 网页模型引擎 V0.14 · AutoJs6 6.8+
// 目标：加载 TapNest 原版导出的「单只桌宠 ZIP」，保留 PNG 图层与 rig.json。
// 无需新增 APK、插件、无障碍、截图、Root、AI 密钥。
// ZIP 只在本机解包到内存，不上传；HTML 由本人的 GitHub 源码提供。
// 注意：本版为实验性移植，复杂模型的表现需要手机实际检查。
// 退出方法：AutoJs6「任务」里停止此脚本。

if (!floaty.hasPermission()) {
  floaty.requestPermission();
  exit();
}
var TAG="TapNest V0.14";
var rawRoot="https://raw.githubusercontent.com/yanlue1211/TapNest/main/experiments/pet-web/";
var rigPage=rawRoot+"rig-viewer.html?v=14", demoPage=rawRoot+"index.html";
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
  var sizeIndex=dialogs.select("🐶 桌宠 V0.8 · 选择显示尺寸",menu);
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
var BASE_EXTRA_DP=76;
var WW=dp(Math.max(PET_SIZE_DP,176)),HH=dp(PET_SIZE_DP+BASE_EXTRA_DP);
var anchorY=Math.max(0,Math.round(device.height*.38)),extraDp=BASE_EXTRA_DP;
var win=floaty.rawWindow(
  <frame w="*" h="*" bg="#00000000">
    <webview id="pet" w="*" h="*" />
  </frame>
);
win.setSize(WW,HH);
win.setPosition(
  Math.max(0,device.width-WW-dp(16)),
  Math.max(0,Math.min(anchorY,device.height-HH))
);
win.setTouchable(true);

// Always reserve space BELOW the sprite; the bubble starts against its
// measured visible bottom and grows downward, never upward. Near the screen
// bottom Android has to nudge the window up to keep the full message visible.
function setExtraBelowPet(requested){
  var nextExtra=Math.max(BASE_EXTRA_DP,Math.min(230,Math.round(Number(requested)||BASE_EXTRA_DP)));
  if(nextExtra===extraDp)return;
  extraDp=nextExtra;
  HH=dp(PET_SIZE_DP+extraDp);
  ui.run(function(){
    try{
      win.setSize(WW,HH);
      win.setPosition(Math.max(0,Math.min(device.width-WW,win.getX())),
        Math.max(0,Math.min(anchorY,device.height-HH)));
    }catch(e){toast("气泡尺寸适配失败："+e);}
  });
}

// Keep the sprite at PET_SIZE_DP; expanding the window never scales artwork.

// Preserve the previously verified V0.4 keyboard fix: the floaty window must
// release input focus after sending/cancelling, hiding IME, or stopping the script.
var focusHeld=false,chatOpen=false,imeWasVisible=false,hiddenChecks=0,focusAt=0;
var checking=false,checkStartedAt=0,shuttingDown=false;
// Long hold without dragging >= 5000ms closes the pet.
var HOLD_TO_CLOSE_MS=5000, holdExitTimer=null, fingerDown=false;
function cancelHoldExit(){
  if(holdExitTimer!==null){clearTimeout(holdExitTimer);holdExitTimer=null;}
}
function closePetAfterHold(){
  if(shuttingDown)return;
  shuttingDown=true;fingerDown=false;cancelHoldExit();
  try { win.disableFocus(); } catch (_) {}
  try { win.close(); } catch (_) {}
  try { engines.myEngine().forceStop(); } catch (_) { try { exit(); } catch (__) {} }
}
function takeFocus(){
  if(shuttingDown||focusHeld)return;
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
    var textResult=String(raw).replace(/^"/,"").replace(/"$/,"");
    var parts=textResult.split("|");
    if(parts.length<2)return;
    var opened=parts[0]==="1",nextExtra=Number(parts[1]);
    if(isFinite(nextExtra))setExtraBelowPet(nextExtra);
    var newlyOpened=opened&&!chatOpen;
    chatOpen=opened;
    if(newlyOpened){
      // The WebView's JS requested focus before the raw floaty was focusable.
      // Grant native focus first, then re-focus the real HTML text input.
      takeFocus();
      ui.run(function(){
        try{
          win.pet.evaluateJavascript(
            "(function(){var i=document.getElementById('message');if(i)i.focus()})()",null
          );
        }catch(_){}
      });
    }
    if(!opened){
      if(focusHeld)releaseFocus();
      return;
    }
    if(!focusHeld)return;
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
function setPickupState(active){
  // Never expose native objects to untrusted remote HTML. Send only one bool.
  ui.run(function(){
    try{
      win.pet.evaluateJavascript(
        "window.PetPreview&&window.PetPreview.setDragging("+ (!!active ? "true":"false") +")",
        null
      );
    }catch(_){}
  });
}
win.pet.setOnTouchListener(function(v,e){
  var a=e.getActionMasked();
  if(a===0){
    cancelHoldExit();
    fingerDown=true;
    sx=e.getRawX();sy=e.getRawY();ox=win.getX();oy=win.getY();
    at=Date.now();drag=false;
    if(chatOpen&&!focusHeld)takeFocus();
    if(!chatOpen){
      // Five continuous seconds without crossing the drag threshold.
      holdExitTimer=setTimeout(function(){
        holdExitTimer=null;
        if(fingerDown&&!drag&&!chatOpen&&!shuttingDown)closePetAfterHold();
      },HOLD_TO_CLOSE_MS);
    }
  }else if(a===2){
    var dx=e.getRawX()-sx,dy=e.getRawY()-sy;
    if(!chatOpen&&!drag&&Math.abs(dx)+Math.abs(dy)>dp(12)){
      drag=true;
      cancelHoldExit();
      setPickupState(true);
    }
    if(drag){
      anchorY=Math.max(0,Math.min(device.height-dp(PET_SIZE_DP+BASE_EXTRA_DP),Math.round(oy+dy)));
      win.setPosition(
        Math.max(0,Math.min(device.width-WW,Math.round(ox+dx))),
        Math.max(0,Math.min(device.height-HH,anchorY))
      );
    }
   }else if(a===1){
    fingerDown=false;cancelHoldExit();
    if(drag){setPickupState(false);drag=false;return false;}
    var heldMs=Date.now()-at;
    if(!drag&&!chatOpen){
      if(heldMs>=HOLD_TO_CLOSE_MS)closePetAfterHold();
      // Short holds no longer open chat. Double-tap is handled by HTML.
      // Native keyboard focus will be granted after double-tap detection.
    }
   }else if(a===3){
    fingerDown=false;cancelHoldExit();
    if(drag){setPickupState(false);drag=false;}
  }
  return false; // do not swallow webpage clicks or drag gestures
});
setInterval(function(){
  // Poll only a tiny status string. Needed for double-tap focus + bubble height.
  if(shuttingDown)return;
  if(checking&&Date.now()-checkStartedAt<2000)return;
  checking=true;checkStartedAt=Date.now();
  ui.run(function(){
    try{
      win.pet.evaluateJavascript(
        "(function(){return window.PetPreview&&window.PetPreview.getState?window.PetPreview.getState():'0|8'})()",cb
      );
    }catch(_){
      checking=false;
      if(focusHeld&&Date.now()-focusAt>60000)releaseFocus();
    }
  });
},300);
events.on("exit",function(){
  fingerDown=false;cancelHoldExit();
  shuttingDown=true;
  try{win.disableFocus()}catch(_){}
  try{win.close()}catch(_){}
});
toast(pack?"🐶 TapNest V0.14 已载入："+chosen.name+"；拖动有专属帧":"🐶 双击聊天，按住5秒关闭");

// Optional bridge server; port only binds loopback. No extra plugin.
// TapNest ↔ SullyOS optional localhost bridge. This code is appended to
// AutoJs6 deskpet V0.14, after the transparent WebView has been created.
// Only 127.0.0.1; no Internet listener, credentials, cloud DB or API calls.
// Pairing PIN is shown ONLY on the user's device, changes every launch.
(function(){
  var PORT=18347;
  var ORIGIN="https://yanlue1211.github.io";
  var random=new java.security.SecureRandom();
  var pin=String(100000+random.nextInt(900000));
  function hexToken(){
    var b=java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE,20);
    random.nextBytes(b);
    var chars="0123456789abcdef",s="";
    for(var i=0;i<b.length;i++){var n=b[i]&255;s+=chars.charAt(n>>4)+chars.charAt(n&15);}
    return s;
  }
  var session="",badPins=0,eventsQueue=[],server=null,ended=false;
  function pushEvent(text){
    var message=String(text||"").trim();
    if(!message)return;
    if(message.length>300)message=message.slice(0,300);
    eventsQueue.push({id:"t"+Date.now()+"-"+Math.floor(Math.random()*10000),text:message,at:Date.now()});
    if(eventsQueue.length>25)eventsQueue.shift();
  }
  function respond(socket,code,obj,origin,method){
    var body=method==="OPTIONS"?"":JSON.stringify(obj||{});
    var bytes=new java.lang.String(body).getBytes("UTF-8");
    var allowed=origin===ORIGIN;
    var header="HTTP/1.1 "+code+"\r\n"+
      "Content-Type: application/json; charset=utf-8\r\n"+
      "Content-Length: "+(method==="OPTIONS"?0:bytes.length)+"\r\n"+
      "Cache-Control: no-store\r\n"+
      "Connection: close\r\n"+
      "X-Content-Type-Options: nosniff\r\n";
    if(allowed)header+="Access-Control-Allow-Origin: "+ORIGIN+"\r\n"+
      "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"+
      "Access-Control-Allow-Headers: Content-Type, X-TapNest-Token\r\n"+
      "Vary: Origin\r\n";
    header+="\r\n";
    var out=socket.getOutputStream();
    out.write(new java.lang.String(header).getBytes("UTF-8"));
    if(method!=="OPTIONS")out.write(bytes);
    out.flush();
  }
  function readLine(input){
    var bytes=new java.io.ByteArrayOutputStream();
    try{
      while(bytes.size()<4096){
        var n=input.read();
        if(n<0||n===10)break;
        if(n!==13)bytes.write(n);
      }
      return bytes.toString("UTF-8");
    }finally{bytes.close()}
  }
  function parseBody(input,bytes){
    var buf=java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE,bytes),at=0;
    while(at<bytes){
      var n=input.read(buf,at,bytes-at);
      if(n<=0)throw Error("truncated request");
      at+=n;
    }
    return String(new java.lang.String(buf,"UTF-8"));
  }
  function handle(socket){
    try{
      socket.setSoTimeout(3500);
      var inp=new java.io.BufferedInputStream(socket.getInputStream());
      var first=readLine(inp).split(" ");
      var method=first[0],path=first[1]||"/",origin="",token="",length=0;
      if(!/^(GET|POST|OPTIONS)$/.test(method))return respond(socket,"405 Method Not Allowed",{error:"method"},"",method);
      for(var i=0;i<65;i++){
        var line=readLine(inp);if(!line)break;
        var index=line.indexOf(":");if(index<0)continue;
        var key=line.slice(0,index).trim().toLowerCase(),val=line.slice(index+1).trim();
        if(key==="origin")origin=val;
        if(key==="content-length")length=Number(val);
        if(key==="x-tapnest-token")token=val;
      }
      if(!Number.isFinite(length)||length<0||length>8192){
        return respond(socket,"413 Payload Too Large",{error:"size"},origin,method);
      }
      if(origin!==ORIGIN){
        return respond(socket,"403 Forbidden",{error:"origin"},origin,method);
      }
      if(method==="OPTIONS")return respond(socket,"204 No Content",{},origin,method);
      var body=method==="POST"?parseBody(inp,length):"";
      var obj={};
      if(body){try{obj=JSON.parse(body)}catch(_){return respond(socket,"400 Bad Request",{error:"json"},origin,method)}}
      if(method==="GET"&&path==="/health"){
        return respond(socket,"200 OK",{ok:true,version:1,paired:!!session},origin,method);
      }
      if(method==="POST"&&path==="/pair"){
        if(badPins>=6)return respond(socket,"429 Too Many Requests",{error:"locked until restart"},origin,method);
        if(String(obj.pin||"")!==pin){
          badPins++;return respond(socket,"403 Forbidden",{error:"pin",remaining:6-badPins},origin,method);
        }
        session=hexToken();badPins=0;
        return respond(socket,"200 OK",{ok:true,token:session,version:1},origin,method);
      }
      if(!session||token!==session)return respond(socket,"401 Unauthorized",{error:"pair first"},origin,method);
      if(method==="GET"&&(path==="/events"||path==="/watch")){
        // /watch is a bounded long-poll. The pending network request can be
        // completed by the native AutoJs6 process as soon as a pet text
        // arrives, instead of waiting for a throttled background JS timer.
        // Browser process suspension can still delay handling the response.
        if(path==="/watch"){
          var until=Date.now()+12000;
          while(!ended && eventsQueue.length===0 && Date.now()<until){
            java.lang.Thread.sleep(120);
          }
        }
        // At-least-once delivery: require an explicit ack from SullyOS.
        return respond(socket,"200 OK",{ok:true,events:eventsQueue.slice(0,12)},origin,method);
      }
      if(method==="POST"&&path==="/ack"){
        var ids=Array.isArray(obj.ids)?obj.ids.filter(x=>typeof x==="string").slice(0,12):[];
        eventsQueue=eventsQueue.filter(x=>ids.indexOf(x.id)<0);
        return respond(socket,"200 OK",{ok:true},origin,method);
      }
      if(method==="POST"&&path==="/speech"){
        var spoken=String(obj.text||"").trim().slice(0,1000);
        if(!spoken)return respond(socket,"400 Bad Request",{error:"text"},origin,method);
        ui.run(function(){
          try{
            win.pet.evaluateJavascript(
              "window.PetPreview&&window.PetPreview.speak("+JSON.stringify(spoken)+")",null
            );
          }catch(e){}
        });
        return respond(socket,"200 OK",{ok:true},origin,method);
      }
      return respond(socket,"404 Not Found",{error:"path"},origin,method);
    }catch(e){
      try{respond(socket,"500 Internal Server Error",{error:"server"},"","GET")}catch(_){}
    }finally{try{socket.close()}catch(_){}}
  }
  try{
    server=new java.net.ServerSocket(PORT,16,java.net.InetAddress.getByName("127.0.0.1"));
    server.setSoTimeout(1200);
  }catch(e){
    toastLog("🐶 桌宠本地桥接端口被占用："+PORT+"，停止旧版桌宠后重试");
    return;
  }
  // Keep accept loop in a worker thread; never block UI or drawing frames.
  threads.start(function(){
    while(!ended){
      try{
        var client=server.accept();
        threads.start((function(c){return function(){handle(c)}})(client));
      }catch(e){
        if(!ended&&!String(e).includes("SocketTimeout"))sleep(300);
      }
    }
  });
  // The WebView is just the rendering/input layer. Message text crosses to
  // native through a single explicitly bounded read; no JavaScriptInterface.
  var draining=false;
  var drainCallback=new JavaAdapter(android.webkit.ValueCallback,{
    onReceiveValue:function(raw){
      draining=false;
      try{
        var value=JSON.parse(String(raw));
        if(value&&session)pushEvent(value);
      }catch(_){}
    }
  });
  var drainTimer=setInterval(function(){
    if(ended||draining||!session)return;
    draining=true;
    ui.run(function(){
      try{
        win.pet.evaluateJavascript(
          "window.PetPreview&&window.PetPreview.popOutgoing?window.PetPreview.popOutgoing():''",
          drainCallback
        );
      }catch(e){draining=false}
    });
  },350);
  events.on("exit",function(){
    ended=true;
    try{clearInterval(drainTimer)}catch(_){}
    try{server.close()}catch(_){}
  });
  toastLog("🐶 已开启本机桥接：127.0.0.1:"+PORT+"\n请在糯米机聊天页点「连接桌宠」，输入配对码："+pin);
})();

setInterval(function(){},1000);
