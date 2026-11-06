// TapNest ↔ SullyOS optional localhost bridge. This code is appended to
// AutoJs6 deskpet V0.13, after the transparent WebView has been created.
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
      if(method==="GET"&&path==="/events"){
        // At-least-once delivery: keep messages until SullyOS confirms success.
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
