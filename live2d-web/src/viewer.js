/**
 * Portable Live2D frontend. Android only provides /model/* read-only files
 * through WebViewAssetLoader. It has no credentials or private chat access.
 * Built with npm run build; web source can later be adapted to SullyOS.
 */
import { Application, extensions } from "pixi.js";
import { configureCubismSDK, Live2DModel, Live2DPlugin } from "untitled-pixi-live2d-engine/cubism";

const message = document.getElementById("status");
const showError = (text) => {
  message.textContent = text;
  message.style.display = "block";
};
const modelPath = new URLSearchParams(location.search).get("model");
let app, model, manifest;
let manualPaused = false;

function fit() {
  if (!app || !model) return;
  const w = Math.max(32, document.documentElement.clientWidth);
  const h = Math.max(32, document.documentElement.clientHeight);
  app.renderer.resize(w, h);
  const naturalW = Math.max(1, model.width / Math.max(0.0001, model.scale.x));
  const naturalH = Math.max(1, model.height / Math.max(0.0001, model.scale.y));
  model.scale.set(Math.min((w * .90) / naturalW, (h * .92) / naturalH, 4));
  model.position.set(w / 2, h / 2);
}
window.tapnestPause = (paused) => {
  manualPaused = Boolean(paused);
  if (app) { if (manualPaused) app.ticker.stop(); else app.ticker.start(); }
};
window.tapnestTap = async () => {
  if (!model || !manifest) return;
  const groups = manifest.FileReferences?.Motions || {};
  for (const name of ["TapBody", "Tap", "Touch", "TapHead"]) {
    if (groups[name]?.length) {
      try { await model.motion(name, 0, 3); return; } catch (_) {}
    }
  }
  const expr = manifest.FileReferences?.Expressions || [];
  if (expr.length) {
    try { await model.expression(expr[Math.floor(Math.random() * expr.length)].Name); } catch (_) {}
  }
};

async function main() {
  if (!modelPath || !modelPath.startsWith("/model/") || !modelPath.endsWith(".model3.json")) {
    throw Error("没有找到有效的 Live2D 模型路径");
  }
  if (!window.Live2DCubismCore) throw Error("Cubism Core 未加载，网络或运行库不兼容");
  const src = new URL(modelPath, location.origin).href;
  const response = await fetch(src, {cache:"no-store"});
  if (!response.ok) throw Error("读取 model3.json 失败");
  manifest = await response.json();

  extensions.add(Live2DPlugin);
  configureCubismSDK({memorySizeMB:32});
  app = new Application();
  await app.init({
    width: Math.max(32, innerWidth), height: Math.max(32, innerHeight),
    backgroundAlpha: 0, preference:"webgl",
    antialias: false, resolution: Math.min(devicePixelRatio || 1, 2),
    autoDensity: true
  });
  app.ticker.maxFPS = 30;
  document.body.appendChild(app.canvas);
  model = await Live2DModel.from(src, { textureOptions: {lod:false}, useHighPrecisionMask:"auto" });
  model.anchor.set(.5, .5);
  app.stage.addChild(model);
  fit();
  window.addEventListener("resize", fit);
  document.addEventListener("visibilitychange", () => window.tapnestPause(document.hidden || manualPaused));
}
main().catch((err) => {
  console.error("[TapNest Live2D]", err);
  showError("Live2D 加载失败：" + (err?.message || String(err)));
});
