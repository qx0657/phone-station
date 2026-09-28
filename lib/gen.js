const fs = require("fs");
const path = require("path");
const QRCode = require(path.join(__dirname, "node_modules", "qrcode"));

const payload = process.argv[2];
const pngPath = process.argv[3];
const htmlPath = process.argv[4];

if (!payload || !pngPath || !htmlPath) {
  console.error("usage: gen.js <payload> <png> <html>");
  process.exit(1);
}

QRCode.toBuffer(payload, {
  errorCorrectionLevel: "H",
  margin: 2,
  width: 900,
  color: { dark: "#000000", light: "#ffffff" },
}).then((png) => {
  fs.writeFileSync(pngPath, png);
  const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>ADB 配对二维码</title>
<style>
  html, body { margin: 0; height: 100%; background: #1c1c1c; color: #111; font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif; }
  .wrap { min-height: 100%; display: flex; align-items: center; justify-content: center; }
  .card { background: #fff; padding: 28px 32px 22px; border-radius: 20px; text-align: center; }
  img { width: 420px; height: 420px; image-rendering: pixelated; }
  h1 { font-size: 22px; font-weight: 650; margin: 18px 0 6px; }
  p { margin: 0; color: #444; font-size: 16px; }
</style>
</head>
<body>
<div class="wrap"><div class="card">
<img alt="ADB pairing QR" src="data:image/png;base64,${png.toString("base64")}">
<h1>用手机扫描这张二维码</h1>
<p>开发者选项 → 无线调试 → 使用二维码配对设备</p>
</div></div>
</body>
</html>
`;
  fs.writeFileSync(htmlPath, html);
  fs.writeFileSync(pngPath, png);
}).catch((error) => {
  console.error(error);
  process.exit(1);
});
