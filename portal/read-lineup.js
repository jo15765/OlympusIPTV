const fs = require("fs");
const path = require("path");
const raw = fs.readFileSync(process.env.TEMP + "/olympus_iptv.preferences_pb");
const text = raw.toString("utf16le");
const start = text.indexOf('{"c99aef54');
if (start < 0) {
  console.log("missing lineup");
  process.exit(1);
}
let depth = 0;
let end = -1;
for (let i = start; i < text.length; i++) {
  const ch = text[i];
  if (ch === "{") depth++;
  else if (ch === "}") {
    depth--;
    if (depth === 0) {
      end = i + 1;
      break;
    }
  }
}
const map = JSON.parse(text.slice(start, end));
const id = Object.keys(map)[0];
const cfg = map[id];
const out = {
  playlistId: id,
  hiddenCountries: cfg.hiddenCountries || [],
  hiddenGroups: (cfg.hiddenGroups || []).length,
  hiddenChannels: (cfg.hiddenChannels || []).length,
  revision: cfg.revision,
  groupNames: Object.keys(cfg.groupNames || {}).length
};
fs.writeFileSync(path.join(__dirname, "tv-lineup.json"), JSON.stringify(cfg));
console.log(JSON.stringify(out, null, 2));
