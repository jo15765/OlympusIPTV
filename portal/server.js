const http = require("http");
const https = require("https");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");
const zlib = require("zlib");
const autoFavorites = require("./auto-favorites");

const PORT = 8443;
const ROOT = __dirname;
const DATA = path.join(ROOT, "data", "store.json");
const PUBLIC = path.join(ROOT, "public");
const PFX = path.join(ROOT, "certs", "portal.pfx");
const PASSPHRASE = "olympus-iptv-local";

function emptyLineup() {
  return {
    groupOrder: [],
    hiddenGroups: [],
    hiddenChannels: [],
    groupNames: {},
    channelNames: {},
    channelOrder: {},
    hiddenCountries: [],
    includeInternational: false,
    namePrefixes: [],
    nameSuffixes: [],
    nameEpg: false,
    revision: 0
  };
}

function blankRecordings() {
  return {
    items: [],
    smb: { host: "", share: "", folder: "Olympus-IPTV", username: "", password: "" },
    guide: {},
    guideRequests: [],
    revision: 0
  };
}

function normalizeRecordings(raw) {
  const source = raw || {};
  const smb = source.smb || {};
  const items = Array.isArray(source.items) ? source.items.map((item) => ({
    id: String(item.id || ""),
    channelId: String(item.channelId || ""),
    channelName: String(item.channelName || ""),
    title: String(item.title || ""),
    startMillis: Number(item.startMillis) || 0,
    endMillis: Number(item.endMillis) || 0,
    destination: item.destination === "smb" ? "smb" : "local",
    status: String(item.status || "scheduled"),
    fileName: String(item.fileName || ""),
    bytes: Number(item.bytes) || 0,
    error: item.error ? String(item.error) : null,
    copied: item.copied === true
  })).filter((item) => item.id && item.channelId && item.endMillis > item.startMillis) : [];
  const guide = {};
  const sourceGuide = source.guide || {};
  for (const [channelId, programs] of Object.entries(sourceGuide)) {
    if (!Array.isArray(programs)) continue;
    guide[String(channelId)] = programs.slice(0, 16).map((program) => ({
      title: String(program.title || ""),
      startMillis: Number(program.startMillis) || 0,
      endMillis: Number(program.endMillis) || 0
    })).filter((program) => program.endMillis > program.startMillis);
  }
  return {
    items,
    smb: {
      host: String(smb.host || "").trim(),
      share: String(smb.share || "").trim(),
      folder: String(smb.folder || "Olympus-IPTV").trim() || "Olympus-IPTV",
      username: String(smb.username || "").trim(),
      password: String(smb.password || "")
    },
    guide,
    guideRequests: Array.isArray(source.guideRequests) ? source.guideRequests.map((id) => String(id || "")).filter(Boolean).slice(0, 4) : [],
    revision: Number(source.revision) || 0
  };
}

function safePart(value) {
  return String(value || "").replace(/[^A-Za-z0-9._-]/g, "");
}

function recordingCopyPath(deviceId, id) {
  return path.join(ROOT, "data", "recordings", safePart(deviceId), safePart(id) + ".ts");
}

let storeCache = null;
let storeMtime = -1;
let storeGeneration = 0;
let savesInFlight = 0;
let saveChain = Promise.resolve();

function loadStore() {
  let mtime = 0;
  try {
    mtime = fs.statSync(DATA).mtimeMs;
  } catch {
    mtime = 0;
  }
  if (storeCache && (savesInFlight > 0 || mtime === storeMtime)) return storeCache;
  try {
    storeCache = JSON.parse(fs.readFileSync(DATA, "utf8"));
  } catch {
    if (!storeCache) storeCache = { accounts: {} };
  }
  storeMtime = mtime;
  storeGeneration += 1;
  return storeCache;
}

function releaseSave() {
  savesInFlight = Math.max(0, savesInFlight - 1);
}

function finishSave(resolve) {
  try {
    storeMtime = fs.statSync(DATA).mtimeMs;
  } catch {
    storeMtime = 0;
  }
  releaseSave();
  resolve();
}

function replaceStoreFile(tmp, resolve) {
  fs.rename(tmp, DATA, (renameError) => {
    if (!renameError) return finishSave(resolve);
    fs.unlink(DATA, () => {
      fs.rename(tmp, DATA, () => finishSave(resolve));
    });
  });
}

function persistStore(store) {
  return new Promise((resolve) => {
    setImmediate(() => {
      let body = "";
      try {
        body = JSON.stringify(store);
      } catch {
        releaseSave();
        resolve();
        return;
      }
      const tmp = DATA + ".tmp";
      fs.mkdir(path.dirname(DATA), { recursive: true }, (mkdirError) => {
        if (mkdirError) {
          releaseSave();
          resolve();
          return;
        }
        fs.writeFile(tmp, body, (writeError) => {
          if (writeError) {
            releaseSave();
            resolve();
            return;
          }
          replaceStoreFile(tmp, resolve);
        });
      });
    });
  });
}

function saveStore(store) {
  storeCache = store;
  storeGeneration += 1;
  savesInFlight += 1;
  saveChain = saveChain.then(() => persistStore(store)).catch(() => {});
}

const channelFacts = new WeakMap();

function factsFor(channels) {
  const list = Array.isArray(channels) ? channels : [];
  const empty = { counts: new Map(), favorites: new Map(), byGroup: new Map(), search: [], logos: new Set(), channelCount: 0 };
  if (!list.length) return empty;
  const cached = channelFacts.get(list);
  if (cached) return cached;
  const counts = new Map();
  const favorites = new Map();
  const byGroup = new Map();
  const logos = new Set();
  const search = new Array(list.length);
  for (let i = 0; i < list.length; i++) {
    const channel = list[i];
    const id = String(channel.groupId || channel.categoryId || "");
    counts.set(id, (counts.get(id) || 0) + 1);
    favorites.set(favoriteKey(channel), channel);
    let rows = byGroup.get(id);
    if (!rows) {
      rows = [];
      byGroup.set(id, rows);
    }
    rows.push(channel);
    const logo = String(channel.logo || channel.logoUrl || "");
    if (logo.startsWith("http://") || logo.startsWith("https://")) logos.add(logo);
    search[i] = {
      channel,
      name: String(channel.name || "").toLowerCase(),
      kind: String(channel.kind || "LIVE").toUpperCase()
    };
  }
  const facts = { counts, favorites, byGroup, search, logos, channelCount: list.length };
  channelFacts.set(list, facts);
  return facts;
}

function rememberCatalog(catalog) {
  if (!catalog || !Array.isArray(catalog.channels)) return;
  const facts = factsFor(catalog.channels);
  const counts = {};
  for (const [id, count] of facts.counts) counts[id] = count;
  catalog.groupCounts = counts;
  catalog.channelCount = facts.channelCount;
}

function publicCatalog(slot) {
  const catalog = (slot && slot.catalog) || {};
  const facts = factsFor(catalog.channels);
  const stored = catalog.groupCounts || {};
  return {
    groups: (catalog.groups || []).map((group) => ({
      id: group.id,
      name: group.name,
      kind: group.kind,
      count: stored[String(group.id)] || facts.counts.get(String(group.id)) || 0
    })),
    countries: catalog.countries || [],
    channelCount: catalog.channelCount || facts.channelCount
  };
}

function slimChannel(channel) {
  return {
    id: String(channel.id || ""),
    name: String(channel.name || ""),
    groupId: String(channel.groupId || channel.categoryId || ""),
    kind: String(channel.kind || "LIVE").toUpperCase(),
    logo: String(channel.logo || channel.logoUrl || "")
  };
}

function favoriteKey(channel) {
  const kind = String(channel.kind || "LIVE").toUpperCase();
  const prefix = kind === "SERIES" ? "series" : kind === "VOD" ? "vod" : "live";
  return prefix + ":" + channel.id;
}

function favoriteLookups(slot) {
  const wanted = new Set(((slot.favorites && slot.favorites.ids) || []).map((id) => String(id)));
  const labels = {};
  const logos = {};
  if (!wanted.size) return { labels, logos };
  const facts = factsFor(slot.catalog && slot.catalog.channels);
  for (const key of wanted) {
    const channel = facts.favorites.get(key);
    if (!channel) continue;
    const renamed = slot.lineup && slot.lineup.channelNames && slot.lineup.channelNames[channel.id];
    labels[key] = renamed || channel.name || channel.id;
    const logo = String(channel.logo || channel.logoUrl || "");
    if (logo) logos[key] = logo;
  }
  return { labels, logos };
}

function publicFavorites(slot) {
  const look = favoriteLookups(slot);
  return Object.assign({ labels: look.labels, logos: look.logos }, slot.favorites);
}


let placeCache = { at: 0, key: "", place: null };

function clientIp(req) {
  return String((req.socket && req.socket.remoteAddress) || "").replace(/^::ffff:/, "");
}

function isPublicIp(ip) {
  if (!ip || ip === "::1" || ip.startsWith("127.") || ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) return false;
  const parts = ip.split(".").map(Number);
  if (parts.length === 4 && parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) return false;
  if (ip.startsWith("fc") || ip.startsWith("fd") || ip.startsWith("fe80")) return false;
  return true;
}

function getJson(target) {
  return new Promise((resolve) => {
    const request = https.get(target, { timeout: 8000 }, (response) => {
      const chunks = [];
      response.on("data", (chunk) => chunks.push(chunk));
      response.on("end", () => {
        try { resolve(JSON.parse(Buffer.concat(chunks).toString("utf8"))); }
        catch (error) { resolve(null); }
      });
    });
    request.on("error", () => resolve(null));
    request.on("timeout", () => {
      request.destroy();
      resolve(null);
    });
  });
}

function lookupPlace(ip) {
  const key = isPublicIp(ip) ? ip : "";
  if (placeCache.place && placeCache.key === key && Date.now() - placeCache.at < 10 * 60 * 1000) {
    return Promise.resolve(placeCache.place);
  }
  const target = key ? "https://ipwho.is/" + encodeURIComponent(key) : "https://ipwho.is/";
  return getJson(target).then((body) => {
    if (!body || body.success !== true) return null;
    const lat = Number(body.latitude);
    const lon = Number(body.longitude);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    const place = { city: String(body.city || ""), lat, lon };
    placeCache = { at: Date.now(), key, place };
    return place;
  });
}

function liveNamed(slot) {
  const groups = new Map(((slot.catalog && slot.catalog.groups) || []).map((group) => [String(group.id), group.name || ""]));
  const wanted = new Set(autoFavorites.GROUPS);
  const out = [];
  for (const channel of ((slot.catalog && slot.catalog.channels) || [])) {
    if (String(channel.kind || "LIVE").toUpperCase() !== "LIVE") continue;
    const group = stripImages(groups.get(String(channel.groupId || channel.categoryId || "")) || "");
    if (!wanted.has(group)) continue;
    out.push({ id: String(channel.id), name: channel.name || "", group });
  }
  return out;
}

function applyAutoPicks(slot, picks) {
  const current = Array.isArray(slot.favorites.ids) ? slot.favorites.ids.map((id) => String(id || "")).filter(Boolean) : [];
  let removed = Array.isArray(slot.favorites.removed) ? slot.favorites.removed.map((id) => String(id || "")).filter(Boolean) : [];
  const ids = current.slice();
  const names = Object.assign({}, (slot.lineup && slot.lineup.channelNames) || {});
  let added = 0;
  let already = 0;
  let labeled = false;
  for (const pick of picks) {
    const key = "live:" + pick.id;
    if (ids.includes(key)) already += 1;
    else {
      ids.push(key);
      added += 1;
    }
    removed = removed.filter((id) => id !== key);
    if (pick.label) {
      names[String(pick.id)] = pick.label;
      labeled = true;
    }
  }
  slot.favorites = { ids, removed, revision: Date.now() };
  if (!slot.lineup) slot.lineup = emptyLineup();
  slot.lineup.channelNames = names;
  if (labeled) slot.lineup.revision = Date.now();
  return { added, already };
}

function searchCatalog(channels, query, kind, limit) {
  const needle = String(query || "").trim().toLowerCase();
  const cap = Math.max(1, Math.min(40, Number(limit) || 40));
  const wanted = String(kind || "").trim().toUpperCase();
  const buckets = wanted ? { [wanted]: [] } : { LIVE: [], VOD: [], SERIES: [] };
  if (needle.length < 2) return [];
  const rows = factsFor(channels).search;
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i];
    if (!row.name.includes(needle)) continue;
    const bucket = buckets[row.kind];
    if (!bucket || bucket.length >= cap) continue;
    bucket.push(slimChannel(row.channel));
    if (Object.values(buckets).every((list) => list.length >= cap)) break;
  }
  return Object.values(buckets).flat();
}

function sameJson(left, right) {
  if (left === right) return true;
  try {
    return JSON.stringify(left) === JSON.stringify(right);
  } catch {
    return false;
  }
}

function deviceNeedsSave(record, deviceId) {
  const id = String(deviceId || "").trim();
  const slot = record.byDevice && record.byDevice[id];
  return !slot || !slot.catalog || !slot.lineup || !slot.favorites || !slot.recordings;
}

function accountKey(email) {
  const key = String(email || "").trim().toLowerCase();
  return key.includes("@") ? key : null;
}

function findAccount(store, email) {
  const key = accountKey(email);
  if (!key || !store.accounts[key]) return null;
  return { key, record: store.accounts[key] };
}

function ensureAccount(store, email) {
  const key = accountKey(email);
  if (!key) return null;
  if (!store.accounts[key]) {
    store.accounts[key] = {
      devices: [],
      catalog: { groups: [], channels: [], countries: [] },
      lineup: emptyLineup()
    };
  }
  if (!store.accounts[key].lineup) store.accounts[key].lineup = emptyLineup();
  if (!store.accounts[key].catalog) store.accounts[key].catalog = { groups: [], channels: [], countries: [] };
  return { key, record: store.accounts[key] };
}

function normalizeLineup(lineup) {
  const source = lineup || {};
  return {
    groupOrder: source.groupOrder || [],
    hiddenGroups: source.hiddenGroups || [],
    hiddenChannels: source.hiddenChannels || [],
    groupNames: source.groupNames || {},
    channelNames: source.channelNames || {},
    channelOrder: source.channelOrder || {},
    hiddenCountries: source.hiddenCountries || [],
    includeInternational: source.includeInternational === true,
    namePrefixes: stripTokens(source.namePrefixes),
    nameSuffixes: stripTokens(source.nameSuffixes),
    nameEpg: source.nameEpg === true,
    revision: Number(source.revision) > 0 ? Number(source.revision) : Date.now()
  };
}

function isMarkToken(token) {
  return /^[:|\-–—\[\]()]+$/.test(token);
}

function stripTokens(raw) {
  const seen = [];
  const parts = Array.isArray(raw) ? raw : String(raw || "").split(",");
  parts.forEach((part, index) => {
    let token = String(part || "");
    if (!Array.isArray(raw) && index > 0 && token.startsWith(" ")) token = token.slice(1);
    if (!token || seen.some((item) => item.toLowerCase() === token.toLowerCase())) return;
    seen.push(token);
  });
  return seen;
}

function stripName(name, prefixes, suffixes) {
  let current = String(name || "").trim();
  if (!current || (!prefixes.length && !suffixes.length)) return current;
  const marks = new Set();
  const phrases = [];
  const prefixWords = [];
  const suffixWords = [];
  for (const token of prefixes) {
    if (isMarkToken(token)) [...token].forEach((ch) => marks.add(ch));
    else if (isPhrase(token)) phrases.push(token);
    else prefixWords.push(token);
  }
  for (const token of suffixes) {
    if (isMarkToken(token)) [...token].forEach((ch) => marks.add(ch));
    else if (isPhrase(token)) phrases.push(token);
    else suffixWords.push(token);
  }
  if (marks.size) {
    let cleared = "";
    for (const ch of current) if (!marks.has(ch)) cleared += ch;
    cleared = cleared.replace(/\s{2,}/g, " ").trim();
    if (cleared) current = cleared;
  }
  if (!phrases.length && !prefixWords.length && !suffixWords.length) return current;
  phrases.sort((a, b) => b.length - a.length);
  prefixWords.sort((a, b) => b.length - a.length);
  suffixWords.sort((a, b) => b.length - a.length);
  for (let pass = 0; pass < 8; pass += 1) {
    let next = current;
    for (const token of phrases) next = cutPhrase(next, token);
    for (const token of prefixWords) next = cutPrefix(next, token);
    for (const token of suffixWords) next = cutSuffix(next, token);
    if (!next.trim() || next === current) return current;
    current = next;
  }
  return current;
}

function sameLetters(name, start, token) {
  if (start < 0 || start + token.length > name.length) return false;
  return name.slice(start, start + token.length).toLowerCase() === token.toLowerCase();
}

function isPhrase(token) {
  return /[^A-Za-z0-9]/.test(token);
}

function cutPhrase(name, token) {
  if (!token || name.length <= token.length) return name;
  const needle = token.toLowerCase();
  const startsWord = /^[A-Za-z0-9]/.test(token);
  const endsWord = /[A-Za-z0-9]$/.test(token);
  let out = "";
  let index = 0;
  let changed = false;
  const lower = name.toLowerCase();
  while (index < name.length) {
    const at = lower.indexOf(needle, index);
    if (at < 0) {
      out += name.slice(index);
      break;
    }
    const before = at > 0 ? name[at - 1] : "";
    const after = name[at + token.length] || "";
    if ((startsWord && /[A-Za-z0-9]/.test(before)) || (endsWord && /[A-Za-z0-9]/.test(after))) {
      out += name.slice(index, at + 1);
      index = at + 1;
      continue;
    }
    out += name.slice(index, at);
    index = at + token.length;
    changed = true;
  }
  return changed && out.trim() ? out : name;
}

function cutPrefix(name, token) {
  if (!token || name.length <= token.length) return name;
  if (!sameLetters(name, 0, token)) return name;
  if (/[A-Za-z0-9]$/.test(token) && /[A-Za-z0-9]/.test(name[token.length] || "")) return name;
  const rest = name.slice(token.length);
  return rest.trim() ? rest : name;
}

function cutSuffix(name, token) {
  if (!token || name.length <= token.length) return name;
  const start = name.length - token.length;
  if (!sameLetters(name, start, token)) return name;
  if (/^[A-Za-z0-9]/.test(token) && /[A-Za-z0-9]/.test(name[start - 1] || "")) return name;
  const head = name.slice(0, start);
  return head.trim() ? head : name;
}

function stripImages(value, modes) {
  const emoji = !modes || modes.emoji !== false;
  const icons = !modes || modes.icons !== false;
  const special = !modes || modes.special !== false;
  const text = String(value || "");
  let out = "";
  for (const ch of text) {
    const point = ch.codePointAt(0);
    const joiner = point === 0x200d || point === 0xfe0f || point === 0xfe0e || point === 0x20e3;
    const picture = /\p{Extended_Pictographic}/u.test(ch);
    const symbol = /\p{So}/u.test(ch) && !picture;
    if ((special && joiner) || (emoji && picture) || (icons && symbol)) continue;
    out += ch;
  }
  const cleaned = out.replace(/\s{2,}/g, " ").trim();
  return cleaned || text.trim();
}

function clipSample(value) {
  const text = String(value || "");
  return text.length <= 72 ? text : text.slice(0, 69).trimEnd() + "…";
}

function stripSlotImages(slot, modes) {
  const lineup = slot.lineup || emptyLineup();
  slot.lineup = lineup;
  const groupNames = Object.assign({}, lineup.groupNames || {});
  const channelNames = Object.assign({}, lineup.channelNames || {});
  const examples = [];
  let changed = 0;
  const note = (before, after) => {
    if (before === after) return;
    changed += 1;
    const sample = { before: clipSample(before), after: clipSample(after) };
    if (examples.length < 4) examples.push(sample);
    else if (changed % 40 === 0) examples[Math.floor(changed / 40) % 4] = sample;
  };
  for (const group of (slot.catalog && slot.catalog.groups) || []) {
    const original = String(group.name || "");
    const display = groupNames[group.id] || original;
    const nextDisplay = stripImages(display, modes);
    const nextOriginal = stripImages(original, modes);
    note(display, nextDisplay);
    if (nextDisplay && nextDisplay !== original) groupNames[group.id] = nextDisplay;
    else delete groupNames[group.id];
    group.name = nextOriginal || original;
  }
  for (const channel of (slot.catalog && slot.catalog.channels) || []) {
    const original = String(channel.name || "");
    const display = channelNames[channel.id] || original;
    const nextDisplay = stripImages(display, modes);
    const nextOriginal = stripImages(original, modes);
    note(display, nextDisplay);
    if (nextDisplay && nextDisplay !== original) channelNames[channel.id] = nextDisplay;
    else delete channelNames[channel.id];
    channel.name = nextOriginal || original;
  }
  lineup.groupNames = groupNames;
  lineup.channelNames = channelNames;
  lineup.revision = Date.now();
  return { changed, examples };
}

function rewriteCatalogNames(slot) {
  const lineup = slot.lineup || emptyLineup();
  const prefixes = lineup.namePrefixes || [];
  const suffixes = lineup.nameSuffixes || [];
  if (!prefixes.length && !suffixes.length) return;
  const channels = slot.catalog && slot.catalog.channels;
  if (Array.isArray(channels)) {
    for (const channel of channels) {
      if (channel && channel.name) channel.name = stripName(channel.name, prefixes, suffixes);
    }
  }
  const names = lineup.channelNames || {};
  for (const id of Object.keys(names)) names[id] = stripName(names[id], prefixes, suffixes);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > 96 * 1024 * 1024) {
        reject(new Error("Body too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      if (!chunks.length) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch (error) {
        reject(error);
      }
    });
    req.on("error", reject);
  });
}

function send(res, status, body, type) {
  const payload = typeof body === "string" ? body : JSON.stringify(body);
  const headers = {
    "Content-Type": type || "application/json; charset=utf-8",
    "Cache-Control": "no-store"
  };
  const accept = res.req && res.req.headers ? String(res.req.headers["accept-encoding"] || "") : "";
  if (payload.length < 8192 || !accept.includes("gzip")) {
    res.writeHead(status, headers);
    res.end(payload);
    return;
  }
  zlib.gzip(payload, (error, zipped) => {
    if (error || res.writableEnded || res.headersSent) {
      if (!res.headersSent && !res.writableEnded) {
        res.writeHead(status, headers);
        res.end(payload);
      }
      return;
    }
    headers["Content-Encoding"] = "gzip";
    headers["Vary"] = "Accept-Encoding";
    res.writeHead(status, headers);
    res.end(zipped);
  });
}

function authorized(record, deviceId) {
  return record.devices.includes(String(deviceId || "").trim());
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function ensureDevice(record, deviceId) {
  const id = String(deviceId || "").trim();
  if (!record.byDevice) record.byDevice = {};
  if (!record.byDevice[id]) {
    record.byDevice[id] = {
      catalog: clone(record.catalog || { groups: [], channels: [], countries: [] }),
      lineup: clone(record.lineup || emptyLineup())
    };
  }
  if (!record.byDevice[id].catalog) {
    record.byDevice[id].catalog = { groups: [], channels: [], countries: [] };
  }
  if (!record.byDevice[id].lineup) record.byDevice[id].lineup = emptyLineup();
  if (!record.byDevice[id].favorites) record.byDevice[id].favorites = { ids: [], revision: 0 };
  if (!record.byDevice[id].recordings) record.byDevice[id].recordings = blankRecordings();
  return record.byDevice[id];
}

function deviceSummaries(record) {
  return record.devices.map((id) => {
    const slot = (record.byDevice && record.byDevice[id]) || {};
    const lineup = slot.lineup || record.lineup || emptyLineup();
    return {
      id,
      hiddenCountries: (lineup.hiddenCountries || []).length,
      hiddenGroups: (lineup.hiddenGroups || []).length,
      hiddenChannels: (lineup.hiddenChannels || []).length
    };
  });
}

function clientIp(req) {
  return String(req.socket.remoteAddress || "").replace(/^::ffff:/, "");
}

function isHomeNetwork(ip) {
  if (ip === "127.0.0.1" || ip === "::1") return true;
  if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) return true;
  if (/^172\.(1[6-9]|2\d|3[0-1])\./.test(ip)) return true;
  const lower = ip.toLowerCase();
  return lower.startsWith("fe80:") || lower.startsWith("fc") || lower.startsWith("fd");
}

function lanUrls() {
  const addresses = [];
  for (const entries of Object.values(os.networkInterfaces())) {
    for (const entry of entries || []) {
      if (entry.family === "IPv4" && !entry.internal) addresses.push(entry.address);
    }
  }
  const score = (ip) => {
    if (ip.startsWith("169.254.") || ip.startsWith("192.168.56.")) return 1;
    if (ip.startsWith("192.168.")) return 4;
    if (ip.startsWith("10.")) return 3;
    return 2;
  };
  return addresses
    .filter((ip, index) => addresses.indexOf(ip) === index)
    .sort((a, b) => score(b) - score(a))
    .map((ip) => `https://${ip}:${PORT}`);
}

const server = https.createServer(
  { pfx: fs.readFileSync(PFX), passphrase: PASSPHRASE },
  async (req, res) => {
    const ip = clientIp(req);
    if (!isHomeNetwork(ip)) {
      return send(res, 403, "Olympus-IPTV dashboard is only available on the home network.");
    }
    const url = new URL(req.url, `https://localhost:${PORT}`);
    try {
      if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/index.html")) {
        return send(res, 200, dashboardPage(), "text/html; charset=utf-8");
      }
      if (req.method === "GET" && url.pathname === "/logo.png") {
        res.writeHead(200, { "Content-Type": "image/png", "Cache-Control": "no-store" });
        fs.createReadStream(path.join(PUBLIC, "logo.png")).pipe(res);
        return;
      }

      if (req.method === "GET" && url.pathname === "/api/logo") {
        const target = String(url.searchParams.get("u") || "");
        if (!publicLogoUrl(target) || !knownLogo(target)) return send(res, 404, { error: "Logo unavailable" });
        return fetchLogo(target, 0, res);
      }

      if (req.method === "GET" && url.pathname === "/api/info") {
        const urls = lanUrls();
        return send(res, 200, { url: urls[0] || `https://127.0.0.1:${PORT}`, urls, privateNetwork: true });
      }

      if (req.method === "POST" && url.pathname === "/api/register") {
        const body = await readBody(req);
        const deviceId = String(body.deviceId || "").trim();
        const store = loadStore();
        const current = ensureAccount(store, body.email);
        if (!current || !deviceId) return send(res, 400, { error: "Email and device ID are required" });
        const retired = Array.isArray(current.record.retired) && current.record.retired.includes(deviceId);
        if (!retired && !current.record.devices.includes(deviceId)) {
          current.record.devices.push(deviceId);
          saveStore(store);
        }
        return send(res, 200, { ok: true, devices: current.record.devices });
      }

      if (req.method === "POST" && url.pathname === "/api/devices") {
        const body = await readBody(req);
        const store = loadStore();
        const found = findAccount(store, body.email);
        if (!found || !found.record.devices.length) {
          return send(res, 401, { error: "That email is not on a TV yet. Open Olympus-IPTV once with this email." });
        }
        return send(res, 200, { ok: true, email: found.key, devices: deviceSummaries(found.record) });
      }

      if (req.method === "POST" && url.pathname === "/api/login") {
        const body = await readBody(req);
        const store = loadStore();
        const found = findAccount(store, body.email);
        const deviceId = String(body.deviceId || "").trim();
        if (!found || !authorized(found.record, deviceId)) {
          return send(res, 401, { error: "That device is not registered yet. Open Olympus-IPTV on the TV once with this email." });
        }
        const dirty = deviceNeedsSave(found.record, deviceId);
        const slot = ensureDevice(found.record, deviceId);
        if (dirty) saveStore(store);
        return send(res, 200, {
          ok: true,
          email: found.key,
          deviceId,
          devices: deviceSummaries(found.record),
          catalog: publicCatalog(slot),
          lineup: slot.lineup,
          favorites: publicFavorites(slot),
          recordings: slot.recordings
        });
      }

      if (req.method === "GET" && url.pathname === "/api/favorites") {
        const store = loadStore();
        const found = findAccount(store, url.searchParams.get("email"));
        const deviceId = String(url.searchParams.get("deviceId") || "").trim();
        if (!found || !authorized(found.record, deviceId)) return send(res, 401, { error: "Device not registered" });
        const dirty = deviceNeedsSave(found.record, deviceId);
        const slot = ensureDevice(found.record, deviceId);
        if (dirty) saveStore(store);
        return send(res, 200, publicFavorites(slot));
      }

      if (req.method === "GET" && url.pathname === "/api/catalog/search") {
        const store = loadStore();
        const found = findAccount(store, url.searchParams.get("email"));
        const deviceId = String(url.searchParams.get("deviceId") || "").trim();
        if (!found || !authorized(found.record, deviceId)) return send(res, 401, { error: "Device not registered" });
        const slot = ensureDevice(found.record, deviceId);
        const channels = searchCatalog(
          (slot.catalog && slot.catalog.channels) || [],
          url.searchParams.get("q"),
          url.searchParams.get("kind"),
          url.searchParams.get("limit")
        );
        return send(res, 200, { channels });
      }

      if (req.method === "GET" && url.pathname === "/api/catalog/group") {
        const store = loadStore();
        const found = findAccount(store, url.searchParams.get("email"));
        const deviceId = String(url.searchParams.get("deviceId") || "").trim();
        const groupId = String(url.searchParams.get("groupId") || "");
        if (!found || !authorized(found.record, deviceId)) return send(res, 401, { error: "Device not registered" });
        const slot = ensureDevice(found.record, deviceId);
        const needle = String(url.searchParams.get("q") || "").trim().toLowerCase();
        const limit = Math.max(1, Math.min(400, Number(url.searchParams.get("limit")) || 400));
        const order = ((slot.lineup && slot.lineup.channelOrder) || {})[groupId] || [];
        const rank = new Map(order.map((id, index) => [String(id), index]));
        const rows = factsFor(slot.catalog && slot.catalog.channels).byGroup.get(groupId) || [];
        const matches = needle
          ? rows.filter((channel) => String(channel.name || "").toLowerCase().includes(needle))
          : rows.slice();
        matches.sort((a, b) => (rank.get(String(a.id)) ?? 999999) - (rank.get(String(b.id)) ?? 999999) || String(a.name || "").localeCompare(String(b.name || "")));
        return send(res, 200, { channels: matches.slice(0, limit).map(slimChannel), total: matches.length });
      }

      if (req.method === "POST" && url.pathname === "/api/favorites/auto") {
        const body = await readBody(req);
        const store = loadStore();
        const found = findAccount(store, body.email);
        const deviceId = String(body.deviceId || "").trim();
        if (!found || !authorized(found.record, deviceId)) return send(res, 401, { error: "Device not registered" });
        const slot = ensureDevice(found.record, deviceId);
        const channels = liveNamed(slot);
        const mode = String(body.mode || "");
        if (mode === "teams") {
          const teams = autoFavorites.teamsFor(String(body.sport || ""), channels).map((team) => team.name);
          return send(res, 200, { ok: true, teams });
        }
        let picks = [];
        let market = "";
        let city = "";
        if (mode === "local") {
          const place = await lookupPlace(clientIp(req));
          if (place) {
            city = place.city;
            const located = autoFavorites.localPicks(channels, place.lat, place.lon);
            market = located.market;
            picks = located.picks;
          } else {
            picks = autoFavorites.nationalPicks(channels);
          }
        } else if (mode === "sports") {
          picks = autoFavorites.sportsPicks(channels);
        } else if (mode === "filo" || mode === "utoob" || mode === "fubo" || mode === "directv") {
          const place = await lookupPlace(clientIp(req));
          const result = autoFavorites.packagePicks(mode, channels, place && place.lat, place && place.lon);
          market = result.market;
          picks = result.picks;
        } else if (mode === "sling-orange" || mode === "sling-blue") {
          const place = await lookupPlace(clientIp(req));
          const result = autoFavorites.slingPicks(
            mode === "sling-orange" ? "orange" : "blue",
            channels,
            place && place.lat,
            place && place.lon
          );
          market = result.market;
          picks = result.picks;
        } else if (mode === "add-teams") {
          const names = Array.isArray(body.teams) ? body.teams : [];
          picks = autoFavorites.picksForTeams(String(body.sport || ""), channels, names);
        } else {
          return send(res, 400, { error: "Unknown favorites request" });
        }
        const counts = applyAutoPicks(slot, picks);
        saveStore(store);
        return send(res, 200, {
          ok: true,
          market,
          city,
          added: counts.added,
          already: counts.already,
          favorites: publicFavorites(slot),
          lineup: slot.lineup
        });
      }

      if (req.method === "POST" && url.pathname === "/api/favorites") {
        const body = await readBody(req);
        const store = loadStore();
        const found = findAccount(store, body.email);
        const deviceId = String(body.deviceId || "").trim();
        if (!found || !authorized(found.record, deviceId)) return send(res, 401, { error: "Device not registered" });
        const slot = ensureDevice(found.record, deviceId);
        const current = Array.isArray(slot.favorites.ids) ? slot.favorites.ids.map((id) => String(id || "")).filter(Boolean) : [];
        const removed = Array.isArray(slot.favorites.removed) ? slot.favorites.removed.map((id) => String(id || "")).filter(Boolean) : [];
        let ids = current.slice();
        let nextRemoved = removed.slice();
        const add = String(body.add || "").trim();
        const remove = String(body.remove || "").trim();
        if (add) {
          nextRemoved = nextRemoved.filter((id) => id !== add);
          if (!ids.includes(add)) ids.push(add);
        } else if (remove) {
          ids = ids.filter((id) => id !== remove);
          if (!nextRemoved.includes(remove)) nextRemoved.push(remove);
        } else if (Array.isArray(body.order)) {
          const posted = [];
          const seen = new Set();
          for (const raw of body.order) {
            const id = String(raw || "").trim();
            if (!id || seen.has(id) || !ids.includes(id)) continue;
            seen.add(id);
            posted.push(id);
          }
          ids = posted.concat(ids.filter((id) => !seen.has(id)));
        } else if (Array.isArray(body.ids)) {
          const incoming = body.ids.map((id) => String(id || "").trim()).filter(Boolean);
          const base = Number(body.baseRevision) || 0;
          const serverRevision = Number(slot.favorites.revision || 0);
          if (base < serverRevision) {
            ids = incoming.filter((id) => !nextRemoved.includes(id));
            for (const id of current) {
              if (!ids.includes(id)) ids.push(id);
            }
          } else {
            ids = incoming;
            nextRemoved = nextRemoved.filter((id) => !ids.includes(id));
          }
        }
        const sameFavorites = ids.length === current.length &&
          nextRemoved.length === removed.length &&
          ids.every((id, index) => id === current[index]) &&
          nextRemoved.every((id, index) => id === removed[index]);
        if (sameFavorites) return send(res, 200, { ok: true, favorites: publicFavorites(slot) });
        slot.favorites = { ids, removed: nextRemoved, revision: Date.now() };
        saveStore(store);
        return send(res, 200, { ok: true, favorites: publicFavorites(slot) });
      }

      if (req.method === "POST" && url.pathname === "/api/catalog") {
        const body = await readBody(req);
        const store = loadStore();
        const found = findAccount(store, body.email);
        const deviceId = String(body.deviceId || "").trim();
        if (!found || !authorized(found.record, deviceId)) return send(res, 401, { error: "Device not registered" });
        const slot = ensureDevice(found.record, deviceId);
        const catalog = slot.catalog;
        let changed = false;
        if (Array.isArray(body.groups) && (body.groups.length || !(catalog.groups || []).length) && !sameJson(catalog.groups, body.groups)) {
          catalog.groups = body.groups;
          changed = true;
        }
        if (Array.isArray(body.channels) && (body.channels.length || !(catalog.channels || []).length)) {
          const stamp = String(body.stamp || "");
          const sameStamp = stamp && stamp === String(catalog.stamp || "") && (catalog.channels || []).length === body.channels.length;
          if (!sameStamp) {
            catalog.channels = body.channels;
            if (stamp) catalog.stamp = stamp;
            changed = true;
          }
        }
        if (Array.isArray(body.countries) && (body.countries.length || !(catalog.countries || []).length) && !sameJson(catalog.countries, body.countries)) {
          catalog.countries = body.countries;
          changed = true;
        }
        if (!changed) return send(res, 200, { ok: true });
        rememberCatalog(catalog);
        slot.catalog = catalog;
        saveStore(store);
        return send(res, 200, { ok: true });
      }

      if (req.method === "GET" && url.pathname === "/api/lineup") {
        const store = loadStore();
        const found = findAccount(store, url.searchParams.get("email"));
        const deviceId = String(url.searchParams.get("deviceId") || "").trim();
        if (!found || !authorized(found.record, deviceId)) {
          return send(res, 401, { error: "Device not registered" });
        }
        const dirty = deviceNeedsSave(found.record, deviceId);
        const slot = ensureDevice(found.record, deviceId);
        if (dirty) saveStore(store);
        return send(res, 200, { lineup: slot.lineup || emptyLineup() });
      }

      if ((req.method === "PUT" || req.method === "POST") && url.pathname === "/api/lineup") {
        const body = await readBody(req);
        const store = loadStore();
        const found = findAccount(store, body.email);
        const deviceId = String(body.deviceId || "").trim();
        if (!found || !authorized(found.record, deviceId)) return send(res, 401, { error: "Device not registered" });
        const slot = ensureDevice(found.record, deviceId);
        const currentRev = Number(slot.lineup && slot.lineup.revision) || 0;
        const baseRev = Number(body.baseRevision) || 0;
        if (baseRev && currentRev > baseRev) {
          return send(res, 200, { ok: true, lineup: slot.lineup, kept: true });
        }
        const incoming = body.lineup || {};
        if (!Number(incoming.revision)) incoming.revision = Date.now();
        const nextLineup = normalizeLineup(incoming);
        if (sameJson(normalizeLineup(slot.lineup), nextLineup)) {
          return send(res, 200, { ok: true, lineup: slot.lineup });
        }
        slot.lineup = nextLineup;
        rewriteCatalogNames(slot);
        saveStore(store);
        return send(res, 200, { ok: true, lineup: slot.lineup });
      }

      if (req.method === "POST" && url.pathname === "/api/names/strip-images") {
        const body = await readBody(req);
        const store = loadStore();
        const found = findAccount(store, body.email);
        const deviceId = String(body.deviceId || "").trim();
        if (!found || !authorized(found.record, deviceId)) return send(res, 401, { error: "Device not registered" });
        const slot = ensureDevice(found.record, deviceId);
        const result = stripSlotImages(slot, {
          emoji: body.emoji !== false,
          icons: body.icons !== false,
          special: body.special !== false
        });
        saveStore(store);
        return send(res, 200, { ok: true, changed: result.changed, examples: result.examples, lineup: slot.lineup });
      }

      if (req.method === "GET" && url.pathname === "/api/recordings") {
        const store = loadStore();
        const found = findAccount(store, url.searchParams.get("email"));
        const deviceId = String(url.searchParams.get("deviceId") || "").trim();
        if (!found || !authorized(found.record, deviceId)) return send(res, 401, { error: "Device not registered" });
        const dirty = deviceNeedsSave(found.record, deviceId);
        const slot = ensureDevice(found.record, deviceId);
        if (dirty) saveStore(store);
        return send(res, 200, slot.recordings);
      }

      if (req.method === "POST" && url.pathname === "/api/recordings") {
        const body = await readBody(req);
        const store = loadStore();
        const found = findAccount(store, body.email);
        const deviceId = String(body.deviceId || "").trim();
        if (!found || !authorized(found.record, deviceId)) return send(res, 401, { error: "Device not registered" });
        const slot = ensureDevice(found.record, deviceId);
        const incoming = normalizeRecordings(body.recordings);
        if (!incoming.smb.password && slot.recordings && slot.recordings.smb) {
          incoming.smb.password = slot.recordings.smb.password || "";
        }
        if (incoming.revision >= Number(slot.recordings.revision || 0)) {
          const previous = slot.recordings.items || [];
          const kept = new Set(incoming.items.map((item) => item.id));
          for (const item of previous) {
            if (kept.has(item.id)) continue;
            const copy = recordingCopyPath(deviceId, item.id);
            if (fs.existsSync(copy)) fs.unlinkSync(copy);
            if (item.destination === "smb" && item.fileName) {
              try {
                await mapShare(slot.recordings.smb);
                const remote = smbFile(slot.recordings.smb, item.fileName);
                if (fs.existsSync(remote)) fs.unlinkSync(remote);
              } catch (error) {
                /* The TV removes the share file on its next sync. */
              }
            }
          }
          slot.recordings = incoming;
          saveStore(store);
        }
        return send(res, 200, { ok: true, recordings: slot.recordings });
      }

      if (req.method === "PUT" && url.pathname === "/api/recordings/file") {
        const store = loadStore();
        const found = findAccount(store, url.searchParams.get("email"));
        const deviceId = String(url.searchParams.get("deviceId") || "").trim();
        const id = safePart(url.searchParams.get("id"));
        if (!found || !authorized(found.record, deviceId) || !id) return send(res, 401, { error: "Device not registered" });
        const dest = recordingCopyPath(deviceId, id);
        fs.mkdirSync(path.dirname(dest), { recursive: true });
        const output = fs.createWriteStream(dest);
        req.pipe(output);
        output.on("finish", () => send(res, 200, { ok: true }));
        output.on("error", () => send(res, 500, { error: "Could not store the recording" }));
        return;
      }

      if (req.method === "GET" && url.pathname === "/api/recordings/file") {
        const store = loadStore();
        const found = findAccount(store, url.searchParams.get("email"));
        const deviceId = String(url.searchParams.get("deviceId") || "").trim();
        const id = safePart(url.searchParams.get("id"));
        if (!found || !authorized(found.record, deviceId) || !id) return send(res, 401, { error: "Device not registered" });
        const slot = ensureDevice(found.record, deviceId);
        const item = (slot.recordings.items || []).find((entry) => entry.id === id);
        if (!item) return send(res, 404, { error: "Recording not found" });
        const downloadName = (item.fileName || "recording.ts").replace(/[^\w. -]/g, "");
        if (item.destination === "smb") {
          try {
            await mapShare(slot.recordings.smb);
            const remote = smbFile(slot.recordings.smb, item.fileName);
            return pipeDownload(res, remote, downloadName);
          } catch (error) {
            return send(res, 500, { error: "Could not open the network share" });
          }
        }
        const local = recordingCopyPath(deviceId, id);
        if (!fs.existsSync(local)) return send(res, 404, { error: "That recording is still on the TV. It can be downloaded after it finishes." });
        return pipeDownload(res, local, downloadName);
      }

      if (req.method === "POST" && url.pathname === "/api/recordings/delete-file") {
        const body = await readBody(req);
        const store = loadStore();
        const found = findAccount(store, body.email);
        const deviceId = String(body.deviceId || "").trim();
        const id = safePart(body.id);
        if (!found || !authorized(found.record, deviceId) || !id) return send(res, 401, { error: "Device not registered" });
        const copy = recordingCopyPath(deviceId, id);
        if (fs.existsSync(copy)) fs.unlinkSync(copy);
        return send(res, 200, { ok: true });
      }

      send(res, 404, { error: "Not found" });
    } catch (error) {
      send(res, 500, { error: error.message || "Server error" });
    }
  }
);

let logoAllow = { generation: -1, urls: new Set() };
let pageCache = { mtime: 0, body: "" };

function dashboardPage() {
  const file = path.join(PUBLIC, "index.html");
  const mtime = fs.statSync(file).mtimeMs;
  if (mtime !== pageCache.mtime) {
    pageCache = { mtime, body: fs.readFileSync(file, "utf8") };
  }
  return pageCache.body;
}

function knownLogo(url) {
  if (!url) return false;
  if (logoAllow.generation === storeGeneration && logoAllow.urls) return logoAllow.urls.has(url);
  const urls = new Set();
  const store = loadStore();
  for (const account of Object.values(store.accounts || {})) {
    const slots = account.byDevice ? Object.values(account.byDevice) : [account];
    for (const slot of slots) {
      for (const logo of factsFor(slot.catalog && slot.catalog.channels).logos) urls.add(logo);
    }
  }
  logoAllow = { generation: storeGeneration, urls };
  return urls.has(url);
}

function publicLogoUrl(value) {
  let parsed;
  try { parsed = new URL(value); } catch { return false; }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return false;
  const host = parsed.hostname.toLowerCase();
  if (host === "localhost" || host.endsWith(".local") || /^(10\.|127\.|0\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[0-1])\.)/.test(host)) return false;
  return true;
}

function logoCacheFile(url) {
  const hash = crypto.createHash("sha1").update(url).digest("hex");
  return path.join(ROOT, "data", "logos", hash);
}

const logoFresh = new Map();

function sendLogoFile(res, file, type) {
  const contentType = type || fs.readFileSync(file + ".type", "utf8");
  res.writeHead(200, { "Content-Type": contentType, "Cache-Control": "public, max-age=86400" });
  fs.createReadStream(file).pipe(res);
}

function cachedLogo(url) {
  const now = Date.now();
  const known = logoFresh.get(url);
  if (known && now < known.until) return known.file ? known : null;
  const file = logoCacheFile(url);
  if (!fs.existsSync(file) || !fs.existsSync(file + ".type")) {
    logoFresh.set(url, { file: null, type: "", until: now + 15000 });
    return null;
  }
  if (now - fs.statSync(file).mtimeMs > 7 * 24 * 60 * 60 * 1000) {
    logoFresh.set(url, { file: null, type: "", until: now + 60000 });
    return null;
  }
  let type = "image/jpeg";
  try { type = fs.readFileSync(file + ".type", "utf8"); } catch { type = "image/jpeg"; }
  const entry = { file, type, until: now + 10 * 60 * 1000 };
  logoFresh.set(url, entry);
  return entry;
}

function fetchLogo(target, depth, res) {
  if (depth > 3) return send(res, 404, { error: "Logo unavailable" });
  const cached = cachedLogo(target);
  if (cached) return sendLogoFile(res, cached.file, cached.type);
  let parsed;
  try { parsed = new URL(target); } catch { return send(res, 404, { error: "Logo unavailable" }); }
  if (!publicLogoUrl(target)) return send(res, 404, { error: "Logo unavailable" });
  const lib = parsed.protocol === "https:" ? https : http;
  const request = lib.get(target, { timeout: 8000, headers: { "User-Agent": "Olympus-IPTV" } }, (upstream) => {
    if (upstream.statusCode >= 300 && upstream.statusCode < 400 && upstream.headers.location) {
      upstream.resume();
      const next = new URL(upstream.headers.location, target).toString();
      return fetchLogo(next, depth + 1, res);
    }
    const type = String(upstream.headers["content-type"] || "").split(";")[0].trim().toLowerCase();
    if (upstream.statusCode !== 200 || !type.startsWith("image/")) {
      upstream.resume();
      if (!res.headersSent) send(res, 404, { error: "Logo unavailable" });
      return;
    }
    const chunks = [];
    let size = 0;
    upstream.on("data", (chunk) => {
      size += chunk.length;
      if (size > 1500000) {
        upstream.destroy();
        return;
      }
      chunks.push(chunk);
    });
    upstream.on("end", () => {
      if (res.headersSent) return;
      if (size > 1500000 || !chunks.length) return send(res, 404, { error: "Logo unavailable" });
      const body = Buffer.concat(chunks);
      const file = logoCacheFile(target);
      try {
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, body);
        fs.writeFileSync(file + ".type", type);
        logoFresh.set(target, { file, type, until: Date.now() + 10 * 60 * 1000 });
      } catch (error) {
        /* Serving the image still works if the cache folder cannot be written. */
      }
      res.writeHead(200, { "Content-Type": type, "Cache-Control": "public, max-age=86400" });
      res.end(body);
    });
  });
  request.on("timeout", () => request.destroy());
  request.on("error", () => { if (!res.headersSent) send(res, 404, { error: "Logo unavailable" }); });
}

function smbFile(smb, fileName) {
  const folder = String((smb && smb.folder) || "Olympus-IPTV").replace(/[\\/]+/g, "\\").replace(/^\\+|\\+$/g, "");
  const host = String(smb.host || "").trim();
  const share = String(smb.share || "").trim();
  return path.win32.join("\\\\" + host + "\\" + share, folder, String(fileName || ""));
}

function mapShare(smb) {
  const root = "\\\\" + String(smb.host || "").trim() + "\\" + String(smb.share || "").trim();
  const username = String(smb.username || "").trim();
  const password = String(smb.password || "");
  const args = username ? ["use", root, password, "/user:" + username] : ["use", root];
  return new Promise((resolve, reject) => {
    const child = spawn("net", args, { windowsHide: true });
    let errorText = "";
    child.stderr.on("data", (chunk) => { errorText += chunk; });
    child.on("close", (code) => {
      if (code === 0 || /already|1219|multiple/i.test(errorText)) resolve();
      else reject(new Error("share"));
    });
  });
}

function pipeDownload(res, filePath, downloadName) {
  if (!fs.existsSync(filePath)) return send(res, 404, { error: "Recording file was not found" });
  const stat = fs.statSync(filePath);
  res.writeHead(200, {
    "Content-Type": "video/mp2t",
    "Content-Length": stat.size,
    "Content-Disposition": "attachment; filename=\"" + downloadName.replace(/"/g, "") + "\"",
    "Cache-Control": "no-store"
  });
  fs.createReadStream(filePath).pipe(res);
}

const urls = lanUrls();
server.listen(PORT, "0.0.0.0", () => {
  console.log("Olympus-IPTV home dashboard");
  console.log(`This PC: https://127.0.0.1:${PORT}`);
  urls.forEach((url) => console.log(`Home network: ${url}`));
  console.log("Requests from outside the home network are refused.");
});
