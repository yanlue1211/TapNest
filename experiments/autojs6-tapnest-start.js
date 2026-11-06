// 🐶 桌宠一键启动（短脚本）：从你自己的 GitHub 仓库加载 V0.7。
var URL="https://raw.githubusercontent.com/yanlue1211/TapNest/main/experiments/autojs6-tapnest-model-v07.js";
try {
  var res=http.get(URL,{timeout:20000});
  if(res.statusCode!==200)throw Error("HTTP "+res.statusCode);
  var code=res.body.string();
  if(code.indexOf('var TAG="TapNest V0.7"')<0)throw Error("不是预期的桌宠 V0.7 文件");
  engines.execScript("🐾 TapNest 网页桌宠",code);
  toast("🐶 已启动桌宠，接下来选择模型和大小");
}catch(e){
  toastLog("桌宠加载失败："+e+"\n检查 Github 网络后重新运行");
}
