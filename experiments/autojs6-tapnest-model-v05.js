// 🐾 TapNest 网页模型引擎 V0.5 · AutoJs6 6.8+
// 目标：加载 TapNest 原版导出的「单只桌宠 ZIP」，保留 PNG 图层与 rig.json。
// 无需新增 APK、插件、无障碍、截图、Root、AI 密钥。
// ZIP 只在本机解包到内存，不上传；HTML 由本人的 GitHub 源码提供。
// 注意：本版为实验性移植，复杂模型的表现需要手机实际检查。
// 退出方法：AutoJs6「任务」里停止此脚本。

if (!floaty.hasPermission()) {
  floaty.requestPermission();
  exit();
}
var TAG="TapNest V0.5";
var rawRoot="https://raw.githubusercontent.com/yanlue1211/TapNest/main/experiments/pet-web/";
var rigPage=rawRoot+"rig-viewer.html", demoPage=rawRoot+"index.html";
var MAX_ZIP=60*1024*1024,MAX_JSON=1024*1024,MAX_PNG=6*1024*1024,MAX_TOTAL=26*1024*1024;
var chosen=null,pack=null,viewUrl=demoPage,webHtml="";

function listPacks() {
  var locations=[
    "/storage/emulated/0/Download",
    "/storage/emulated/0/Downloads",
    "/storage/emulated/0/Documents",
    "/storage/emulated/0/脚本"
  ];
  var entries=[],found={};
  for(var i=0;i<locations.length;i++){
    var dir=new java.io.File(locations[i]);
    var list=dir.listFiles();
    if(!list)continue;
    for(var j=0;j<list.length;j++){
      var f=list[j],name=String(f.getName()),path=String(f.getAbsolutePath());
      if(!f.isFile()||!/.zip$/i.test(name)||f.length()<=100||f.length()>MAX_ZIP||found[path])continue;
      found[path]=true;entries.push({path:path,name:name,modified:Number(f.lastModified())});
    }
  }
  entries.sort(function(a,b){return b.modified-a.modified});
  return entries.slice(0,40);
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
  var choices=listPacks();
  var labels=["🐶 继续使用演示小狗（不用 ZIP）"];
  choices.forEach(function(f){labels.push("📦 "+f.name)});
  var selection=dialogs.select("桌宠 V0.5 · 选择模型",labels);
  if(selection<0)exit();
  if(selection>0){
    chosen=choices[selection-1];
    pack=readPetZip(chosen.path);
    viewUrl=rigPage;
  }
}catch(e){
  toastLog("模型导入失败："+e+"\n请保存 TapNest 导出的 ZIP 到「下载」文件夹后重试。");
  exit();
}

try{
  var response=http.get(viewUrl,{timeout:20000});
  if(response.statusCode!==200)throw Error("网页 HTTP "+response.statusCode);
  webHtml=response.body.string();
  if(webHtml.indexOf("window.PetPreview")<0)throw Error("网页格式不对");
  if(pack){
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
var WW=dp(220),HH=dp(220);
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

// Preserve the previously verified V0.4 keyboard fix: the floaty window must
// release input focus after sending/cancelling, hiding IME, or stopping the script.
var focusHeld=false,chatOpen=false,imeWasVisible=false,hiddenChecks=0,focusAt=0;
var checking=false,checkStartedAt=0,shuttingDown=false;
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
    var opened=String(raw)==="true";
    chatOpen=opened;
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
toast(pack?"🐶 已载入 TapNest 模型："+chosen.name:"🐶 演示小狗启动（未导入模型）");
setInterval(function(){},1000);
