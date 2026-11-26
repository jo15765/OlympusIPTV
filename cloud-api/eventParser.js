"use strict";

const PRE_EVENT_WINDOW_MS = 15 * 60 * 1000;
const DEFAULT_EVENT_DURATION_MS = 165 * 60 * 1000;
const BASKETBALL_EVENT_DURATION_MS = 135 * 60 * 1000;
const SOCCER_EVENT_DURATION_MS = 130 * 60 * 1000;
const HIGH_SCHOOL_EVENT_DURATION_MS = 120 * 60 * 1000;
const SHORT_EVENT_DURATION_MS = 90 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;

function eventDurationMs(title, rawChannel = "") {
  const lower = `${String(title || "")} ${String(rawChannel || "")}`.toLowerCase();
  if (lower.includes("press conference") || lower.includes("weigh-in") || lower.includes("weigh in") || lower.includes("open mic")) {
    return SHORT_EVENT_DURATION_MS;
  }
  if (lower.includes("nfhs") || lower.includes("high school") || lower.includes("junior varsity") || lower.includes("sophomore") || lower.includes("freshman")) {
    return HIGH_SCHOOL_EVENT_DURATION_MS;
  }
  if (lower.includes("dazn") || lower.includes("fanatiz") || lower.includes("soccer") || lower.includes("premier league") ||
      lower.includes("champions league") || lower.includes("mls") || lower.includes("nwsl") || lower.includes("efl") ||
      lower.includes("fa cup") || lower.includes("laliga") || lower.includes("serie a") || lower.includes("bundesliga")) {
    return SOCCER_EVENT_DURATION_MS;
  }
  if (lower.includes("ncaab") || lower.includes("ncaaw") || lower.includes("basketball") || lower.includes("espn+") || lower.includes("disney+")) {
    return BASKETBALL_EVENT_DURATION_MS;
  }
  return DEFAULT_EVENT_DURATION_MS;
}

const MONTHS = {
  jan: 0, january: 0,
  feb: 1, february: 1,
  mar: 2, march: 2,
  apr: 3, april: 3,
  may: 4,
  jun: 5, june: 5,
  jul: 6, july: 6,
  aug: 7, august: 7,
  sep: 8, sept: 8, september: 8,
  oct: 9, october: 9,
  nov: 10, november: 10,
  dec: 11, december: 11
};

const ZONE_MAP = {
  ET: "America/New_York", EST: "America/New_York", EDT: "America/New_York",
  TORONTO: "America/Toronto",
  CT: "America/Chicago", CST: "America/Chicago", CDT: "America/Chicago",
  MT: "America/Denver", MST: "America/Denver", MDT: "America/Denver",
  PT: "America/Los_Angeles", PST: "America/Los_Angeles", PDT: "America/Los_Angeles",
  AST: "America/Halifax", ADT: "America/Halifax",
  UK: "Europe/London", BST: "Europe/London", WET: "Europe/London", WEST: "Europe/London",
  LONDON: "Europe/London", DUBLIN: "Europe/Dublin", LISBON: "Europe/Lisbon",
  CET: "Europe/Berlin", CEST: "Europe/Berlin",
  PARIS: "Europe/Paris", BERLIN: "Europe/Berlin", MADRID: "Europe/Madrid", ROME: "Europe/Rome",
  SYDNEY: "Australia/Sydney",
  GMT: "UTC", UTC: "UTC"
};

const ISO_DATETIME = /\b(\d{4})-(\d{2})-(\d{2})[T\s]+(\d{1,2}):(\d{2})(?::\d{2})?\s*(AM|PM)?\s*([A-Za-z]{2,10})?/i;
const AT_DAY_MONTH = /@\s*(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3,9})(?:,?\s+(\d{4}))?[\s,\-|]+(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?\s*([A-Za-z]{2,10})?/i;
const AT_MONTH_DAY = /@\s*([A-Za-z]{3,9})\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?[\s,\-|]+(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?\s*([A-Za-z]{2,10})?/i;
const AT_NUMERIC = /@\s*(\d{1,2})[./\-](\d{1,2})(?:[./\-](\d{2,4}))?[\s,\-|]+(\d{1,2}):(\d{2})\s*(AM|PM)?\s*([A-Za-z]{2,10})?/i;
const PAREN_MONTH = /\(\s*([A-Za-z]{3,9})\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?[\s,\-|]+(\d{1,2}):(\d{2})\s*(AM|PM)?\s*([A-Za-z]{2,10})?\s*\)/i;
const PAREN_DOT = /\(\s*(\d{1,2})[./\-](\d{1,2})(?:[./\-](\d{2,4}))?[\s,\-|]+(\d{1,2}):(\d{2})\s*(AM|PM)?\s*([A-Za-z]{2,10})?\s*\)/i;
const BARE_MONTH = /\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?[\s,\-|]+(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?\s*([A-Za-z]{2,10})?/i;
const BARE_DAY_MONTH = /\b(\d{1,2})(?:st|nd|rd|th)?\s+(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)(?:,?\s+(\d{4}))?[\s,\-|]+(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?\s*([A-Za-z]{2,10})?/i;
const BARE_NUMERIC = /\b(\d{1,2})[./\-](\d{1,2})(?:[./\-](\d{2,4}))?[\s,\-|]+(\d{1,2}):(\d{2})\s*(AM|PM)?\s*([A-Za-z]{2,10})?/i;
const TIME_ONLY = /(?:@\s*|\b)(\d{1,2}):(\d{2})\s*(AM|PM)\s*(ET|EST|EDT|CT|CST|CDT|MT|MST|MDT|PT|PST|PDT|GMT|UTC|UK|BST|CET|CEST|LONDON)?\b/i;

const PLACEHOLDER_EXACT = new Set([
  "no event", "no event today", "no game today", "signing off", "channel off air",
  "ended", "streaming on this service", "check fixtures", "off air", "tba", "tbd",
  "no live event right now", "no broadcasting"
]);

function isPlaceholderEvent(text) {
  if (!text) return true;
  const t = String(text).trim();
  if (!t) return true;
  const lower = t.toLowerCase();
  if (PLACEHOLDER_EXACT.has(lower)) return true;
  if (lower.endsWith("no broadcasting")) return true;
  if (lower.startsWith("sendepause") || lower.startsWith("live in ") || lower.startsWith("upcoming (")) return true;
  if (lower.startsWith("match ") && t.length <= 10) return true;
  if (lower.startsWith("psc match ") && t.length <= 14) return true;
  const stripped = t.replace(/^[\s\-:|–—]+|[\s\-:|–—]+$/g, "");
  return !stripped || /^\d+$/.test(stripped);
}

function to24(hour, ampm) {
  if (!ampm) return Math.max(0, Math.min(23, hour));
  const base = hour % 12;
  return ampm.toUpperCase() === "PM" ? base + 12 : base;
}

function tzOffsetMs(ianaZone, utcGuessMs) {
  try {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone: ianaZone,
      year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", second: "2-digit",
      hour12: false
    }).formatToParts(new Date(utcGuessMs));
    const map = {};
    for (const p of parts) map[p.type] = p.value;
    const h = Number(map.hour) === 24 ? 0 : Number(map.hour);
    const asUtc = Date.UTC(Number(map.year), Number(map.month) - 1, Number(map.day), h, Number(map.minute), Number(map.second));
    return asUtc - utcGuessMs;
  } catch (_) {
    return 0;
  }
}

function buildTimestamp({ day, monthName, monthNumber, year, hour, minute, ampm, zone, nowMs }) {
  const dayNum = parseInt(day, 10);
  const monthIdx = monthName != null
    ? MONTHS[String(monthName).toLowerCase()]
    : (parseInt(monthNumber, 10) - 1);
  const hourNum = parseInt(hour, 10);
  const minNum = parseInt(minute, 10);
  if (!Number.isFinite(dayNum) || !Number.isFinite(monthIdx) || monthIdx < 0 || monthIdx > 11) return null;
  if (!Number.isFinite(hourNum) || !Number.isFinite(minNum)) return null;

  const now = new Date(nowMs);
  let yr = year ? parseInt(year, 10) : now.getUTCFullYear();
  if (yr < 100) yr += 2000;

  const h24 = to24(hourNum, ampm);
  const iana = ZONE_MAP[String(zone || "").trim().toUpperCase()] || "America/New_York";

  const makeUtc = (y) => {
    const guess = Date.UTC(y, monthIdx, dayNum, h24, minNum, 0);
    const off1 = tzOffsetMs(iana, guess);
    const adjusted = guess - off1;
    const off2 = tzOffsetMs(iana, adjusted);
    return guess - off2;
  };

  let ts = makeUtc(yr);
  if (!year && ts < nowMs - 200 * DAY_MS) {
    ts = makeUtc(yr + 1);
  }
  return ts;
}

function parseEventStartMillis(rawName, nowMs = Date.now()) {
  const raw = String(rawName || "").trim();
  if (!raw) return null;
  const lastAt = raw.lastIndexOf("@");
  const slice = lastAt >= 0 ? raw.slice(lastAt) : raw;

  const tryMatch = (str) => {
    let m;
    if ((m = ISO_DATETIME.exec(str))) {
      return buildTimestamp({ year: m[1], monthNumber: m[2], day: m[3], hour: m[4], minute: m[5], ampm: m[6], zone: m[7] || "CET", nowMs });
    }
    if ((m = AT_DAY_MONTH.exec(str))) {
      return buildTimestamp({ day: m[1], monthName: m[2], year: m[3], hour: m[4], minute: m[5] || "00", ampm: m[6], zone: m[7], nowMs });
    }
    if ((m = AT_MONTH_DAY.exec(str))) {
      return buildTimestamp({ monthName: m[1], day: m[2], year: m[3], hour: m[4], minute: m[5] || "00", ampm: m[6], zone: m[7], nowMs });
    }
    if ((m = AT_NUMERIC.exec(str))) {
      return buildTimestamp({ monthNumber: m[1], day: m[2], year: m[3], hour: m[4], minute: m[5], ampm: m[6], zone: m[7], nowMs });
    }
    if ((m = PAREN_MONTH.exec(str))) {
      return buildTimestamp({ monthName: m[1], day: m[2], year: m[3], hour: m[4], minute: m[5], ampm: m[6], zone: m[7], nowMs });
    }
    if ((m = PAREN_DOT.exec(str))) {
      return buildTimestamp({ monthNumber: m[1], day: m[2], year: m[3], hour: m[4], minute: m[5], ampm: m[6], zone: m[7], nowMs });
    }
    if ((m = BARE_MONTH.exec(str))) {
      if (m[5] != null || m[6] != null) {
        return buildTimestamp({ monthName: m[1], day: m[2], year: m[3], hour: m[4], minute: m[5] || "00", ampm: m[6], zone: m[7], nowMs });
      }
    }
    if ((m = BARE_DAY_MONTH.exec(str))) {
      if (m[5] != null || m[6] != null) {
        return buildTimestamp({ day: m[1], monthName: m[2], year: m[3], hour: m[4], minute: m[5] || "00", ampm: m[6], zone: m[7], nowMs });
      }
    }
    if ((m = BARE_NUMERIC.exec(str))) {
      return buildTimestamp({ monthNumber: m[1], day: m[2], year: m[3], hour: m[4], minute: m[5], ampm: m[6], zone: m[7], nowMs });
    }
    if ((m = TIME_ONLY.exec(str))) {
      const iana = ZONE_MAP[String(m[4] || "").trim().toUpperCase()] || "America/New_York";
      const parts = new Intl.DateTimeFormat("en-US", {
        timeZone: iana, year: "numeric", month: "2-digit", day: "2-digit"
      }).formatToParts(new Date(nowMs));
      const map = {};
      for (const p of parts) map[p.type] = p.value;
      return buildTimestamp({
        monthNumber: map.month, day: map.day, year: map.year,
        hour: m[1], minute: m[2], ampm: m[3], zone: m[4], nowMs
      });
    }
    return null;
  };

  return tryMatch(slice) ?? (slice !== raw ? tryMatch(raw) : null);
}

function stripLeadingRegionPrefix(text) {
  const m = /^(?:[A-Z]{2,3}|INTL|LATAM|EURO|ENG|ESP|FRE|GER)\s*[|:]\s+/i.exec(text);
  if (!m) return text;
  const rest = text.slice(m[0].length).trim();
  return (rest.includes(":") || rest.includes("|") || rest.includes("@")) ? rest : text;
}

function inspectEvent(rawChannel, nowMs = Date.now()) {
  const raw = String(rawChannel || "").trim();
  if (!raw) return { isEventSlot: false, eventTitle: null, eventStartMs: 0, eventEndMs: 0 };
  const ch = stripLeadingRegionPrefix(raw);

  const firstPipe = ch.indexOf("|");
  const rawColon = ch.indexOf(":");
  const colonIdx = (rawColon > 0 && rawColon + 1 < ch.length && /\d/.test(ch[rawColon - 1]) && /\d/.test(ch[rawColon + 1]))
    ? -1
    : rawColon;
  const sepIdx = (colonIdx > 0 && (firstPipe < 0 || colonIdx < firstPipe))
    ? colonIdx
    : (firstPipe > 0 ? firstPipe : -1);

  if (sepIdx > 0) {
    const left = ch.slice(0, sepIdx).trim();
    const rightRaw = ch.slice(sepIdx + 1).trim();
    const endsWithDigits = left.length > 0 && /\d$/.test(left) && /[A-Za-z]/.test(left);
    const inlineStart = parseEventStartMillis(rightRaw, nowMs);
    if (endsWithDigits || inlineStart != null) {
      const atIdx = rightRaw.lastIndexOf("@");
      const inlineEvent = (atIdx > 0 ? rightRaw.slice(0, atIdx) : rightRaw)
        .replace(/^[\s\-:|–—]+|[\s\-:|–—]+$/g, "");
      if (isPlaceholderEvent(inlineEvent)) {
        return { isEventSlot: true, eventTitle: null, eventStartMs: 0, eventEndMs: 0 };
      }
      const start = inlineStart != null ? inlineStart : -1;
      const end = start > 0 ? start + eventDurationMs(inlineEvent, ch) : 0;
      return { isEventSlot: true, eventTitle: inlineEvent, eventStartMs: start, eventEndMs: end };
    }
    if (isPlaceholderEvent(rightRaw)) {
      return { isEventSlot: true, eventTitle: null, eventStartMs: 0, eventEndMs: 0 };
    }
  }

  const bareStart = parseEventStartMillis(ch, nowMs);
  if (bareStart != null) {
    const atIdx = ch.lastIndexOf("@");
    const inlineEvent = (atIdx > 0 ? ch.slice(0, atIdx) : ch)
      .replace(/^[\s\-:|–—]+|[\s\-:|–—]+$/g, "");
    return {
      isEventSlot: true,
      eventTitle: inlineEvent || ch,
      eventStartMs: bareStart,
      eventEndMs: bareStart + DEFAULT_EVENT_DURATION_MS
    };
  }

  return { isEventSlot: false, eventTitle: null, eventStartMs: 0, eventEndMs: 0 };
}

function isLiveNow(status, nowMs = Date.now()) {
  if (!status || !status.isEventSlot) return true;
  if (status.eventStartMs === 0) return false;
  if (status.eventStartMs < 0) return true;
  return nowMs >= (status.eventStartMs - PRE_EVENT_WINDOW_MS) && nowMs < status.eventEndMs;
}

module.exports = {
  PRE_EVENT_WINDOW_MS,
  DEFAULT_EVENT_DURATION_MS,
  parseEventStartMillis,
  inspectEvent,
  isLiveNow,
  isPlaceholderEvent
};

