const fs = require("fs");
const path = require("path");
const cfg = JSON.parse(fs.readFileSync(path.join(__dirname, "tv-lineup.json"), "utf8"));
cfg.revision = Date.now();
const storePath = path.join(__dirname, "data", "store.json");
const store = JSON.parse(fs.readFileSync(storePath, "utf8"));
const email = process.argv[2];
const deviceId = process.argv[3];
if (!email || !deviceId) {
  console.error("Usage: node apply-tv-lineup.js <email> <deviceId>");
  process.exit(1);
}
const record = store.accounts[email];
if (!record) {
  console.error("No account for that email.");
  process.exit(1);
}
record.lineup = cfg;
if (!record.byDevice) record.byDevice = {};
if (!record.byDevice[deviceId]) record.byDevice[deviceId] = { catalog: record.catalog, lineup: cfg };
record.byDevice[deviceId].lineup = cfg;
fs.writeFileSync(storePath, JSON.stringify(store));
console.log(JSON.stringify({
  hiddenCountries: cfg.hiddenCountries.length,
  revision: cfg.revision
}));
