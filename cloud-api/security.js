"use strict";

const crypto = require("crypto");
const dns = require("dns").promises;
const net = require("net");
const pino = require("pino");
const { z } = require("zod");

const logger = pino({
  level: process.env.LOG_LEVEL || "info",
  redact: {
    paths: [
      "req.headers['x-device-token']",
      "req.headers['x-olympus-api-key']",
      "req.headers.authorization",
      "password",
      "*.password"
    ],
    censor: "[REDACTED]"
  }
});

const RAW_CRED_KEY = String(process.env.CREDENTIALS_KEY || "").trim();
const CRED_KEY = RAW_CRED_KEY
  ? crypto.createHash("sha256").update(RAW_CRED_KEY).digest()
  : null;

const ENC_PREFIX = "enc:v1:";

function encryptSecret(value) {
  if (!CRED_KEY || value == null || value === "") return value;
  const str = String(value);
  if (str.startsWith(ENC_PREFIX)) return str;
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", CRED_KEY, iv);
  const ct = Buffer.concat([cipher.update(str, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return `${ENC_PREFIX}${iv.toString("base64")}:${tag.toString("base64")}:${ct.toString("base64")}`;
}

function decryptSecret(value) {
  if (value == null || value === "") return value;
  const str = String(value);
  if (!str.startsWith(ENC_PREFIX)) return str;
  if (!CRED_KEY) {
    logger.warn("Encrypted credential encountered but CREDENTIALS_KEY is not set");
    return "";
  }
  try {
    const parts = str.slice(ENC_PREFIX.length).split(":");
    if (parts.length !== 3) return "";
    const iv = Buffer.from(parts[0], "base64");
    const tag = Buffer.from(parts[1], "base64");
    const ct = Buffer.from(parts[2], "base64");
    const decipher = crypto.createDecipheriv("aes-256-gcm", CRED_KEY, iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(ct), decipher.final()]).toString("utf8");
  } catch (err) {
    logger.error({ err: err.message }, "Failed to decrypt provider secret");
    return "";
  }
}

function encryptLogin(login) {
  if (!login || typeof login !== "object") return login;
  const out = Object.assign({}, login);
  if (out.password) out.password = encryptSecret(out.password);
  if (out.xtreamPassword) out.xtreamPassword = encryptSecret(out.xtreamPassword);
  if (out.url && out.mode === "m3u") out.url = encryptSecret(out.url);
  return out;
}

function decryptLogin(login) {
  if (!login || typeof login !== "object") return login;
  const out = Object.assign({}, login);
  if (out.password) out.password = decryptSecret(out.password);
  if (out.xtreamPassword) out.xtreamPassword = decryptSecret(out.xtreamPassword);
  if (out.url) out.url = decryptSecret(out.url);
  return out;
}

function isBlockedIpv4(ip) {
  const parts = ip.split(".").map(Number);
  if (parts.length !== 4 || parts.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return true;
  const [a, b] = parts;
  if (a === 0 || a === 10 || a === 127) return true;
  if (a === 169 && b === 254) return true;
  if (a === 172 && b >= 16 && b <= 31) return true;
  if (a === 192 && b === 168) return true;
  if (a === 100 && b >= 64 && b <= 127) return true;
  if (a >= 224) return true;
  return false;
}

function isBlockedIpv6(ip) {
  const norm = ip.toLowerCase();
  if (norm === "::1" || norm === "::") return true;
  if (norm.startsWith("fc") || norm.startsWith("fd")) return true;
  if (norm.startsWith("fe80:") || norm.startsWith("fe8") || norm.startsWith("fe9") || norm.startsWith("fea") || norm.startsWith("feb")) return true;
  if (norm.startsWith("::ffff:")) {
    return isBlockedIpv4(norm.slice(7));
  }
  return false;
}

function isBlockedIp(ip) {
  const kind = net.isIP(ip);
  if (kind === 4) return isBlockedIpv4(ip);
  if (kind === 6) return isBlockedIpv6(ip);
  return true;
}

async function assertSafeProviderUrl(rawUrl) {
  const str = String(rawUrl || "").trim();
  if (!str) throw new Error("Provider URL is required");
  let parsed;
  try {
    parsed = new URL(str);
  } catch (_) {
    throw new Error("Invalid provider URL");
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error("Provider URL must use http:// or https://");
  }
  const host = parsed.hostname.toLowerCase();
  if (
    !host ||
    host === "localhost" ||
    host.endsWith(".localhost") ||
    host.endsWith(".local") ||
    host.endsWith(".internal")
  ) {
    throw new Error("Private or local provider hosts are not allowed");
  }
  if (net.isIP(host)) {
    if (isBlockedIp(host)) throw new Error("Private or reserved IP addresses are not allowed");
    return parsed.toString();
  }
  try {
    const records = await dns.lookup(host, { all: true });
    if (!records || records.length === 0) throw new Error("Could not resolve provider host");
    for (const rec of records) {
      if (isBlockedIp(rec.address)) {
        throw new Error("Provider host resolves to a private or reserved IP address");
      }
    }
  } catch (err) {
    if (err && /private|reserved|not allowed|Invalid|resolve/i.test(err.message)) throw err;
    throw new Error("Could not resolve provider host");
  }
  return parsed.toString();
}

const setupSchema = z.object({
  email: z.string().trim().email("Valid email required").max(254),
  deviceId: z.string().trim().min(1, "Enter the device ID shown on the TV").max(128),
  zip: z.string().transform((v) => v.replace(/\D/g, "").slice(0, 5)).refine((v) => v.length === 5, {
    message: "Email and a 5-digit ZIP are required"
  }),
  mode: z.enum(["xtream", "m3u"]).default("xtream"),
  host: z.string().trim().max(512).optional().default(""),
  url: z.string().trim().max(2048).optional().default(""),
  username: z.string().trim().max(256).optional().default(""),
  password: z.string().max(256).optional().default(""),
  includeVod: z.boolean().optional().default(true),
  includeInternational: z.boolean().optional().default(false)
});

module.exports = {
  logger,
  encryptLogin,
  decryptLogin,
  assertSafeProviderUrl,
  setupSchema,
  encryptionEnabled: Boolean(CRED_KEY)
};

