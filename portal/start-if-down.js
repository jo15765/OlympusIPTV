const fs = require("fs");
const net = require("net");
const path = require("path");
const { spawn } = require("child_process");

const PORT = 8443;
const ROOT = __dirname;

function portOpen() {
  return new Promise((resolve) => {
    const socket = net.connect({ host: "127.0.0.1", port: PORT });
    const finish = (open) => {
      socket.destroy();
      resolve(open);
    };
    socket.setTimeout(400);
    socket.once("connect", () => finish(true));
    socket.once("timeout", () => finish(false));
    socket.once("error", () => finish(false));
  });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function main() {
  if (await portOpen()) {
    console.log("Home dashboard is already running at https://127.0.0.1:" + PORT);
    return;
  }
  const logPath = path.join(ROOT, "data", "dashboard.log");
  fs.mkdirSync(path.dirname(logPath), { recursive: true });
  const log = fs.openSync(logPath, "a");
  const child = spawn(process.execPath, ["server.js"], {
    cwd: ROOT,
    detached: true,
    windowsHide: true,
    stdio: ["ignore", log, log]
  });
  child.unref();
  for (let attempt = 0; attempt < 40; attempt += 1) {
    await sleep(250);
    if (await portOpen()) {
      console.log("Home dashboard started at https://127.0.0.1:" + PORT);
      return;
    }
  }
  console.log("Home dashboard did not open port " + PORT + ". The app will still install.");
}

main().catch((error) => {
  console.log("Home dashboard was not started. " + error.message);
});
