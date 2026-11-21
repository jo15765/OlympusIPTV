require("dotenv").config();
const fs = require("fs");
const path = require("path");
const db = require("./db");

async function migrate() {
  const storePath = path.join(__dirname, "data", "store.json");
  if (!fs.existsSync(storePath)) {
    console.log("No local portal/data/store.json found. Nothing to migrate.");
    process.exit(0);
  }
  const raw = JSON.parse(fs.readFileSync(storePath, "utf8"));
  const accounts = raw.accounts || {};
  let migrated = 0;

  for (const [email, entry] of Object.entries(accounts)) {
    const account = await db.ensureAccount(email);
    const devices = entry.devices || {};
    for (const [deviceCode, dev] of Object.entries(devices)) {
      await db.registerDevice(account.id, deviceCode, "android");
      if (dev.lineup) {
        await db.upsertLineup(account.id, deviceCode, dev.lineup);
      }
      if (dev.favorites) {
        await db.upsertFavorites(account.id, dev.favorites);
      }
      if (dev.catalog) {
        await db.upsertSnapshot(account.id, deviceCode, dev.catalog);
      }
      migrated += 1;
    }
  }
  console.log(`Migrated ${migrated} device setups into PostgreSQL successfully.`);
  await db.pool.end();
}

migrate().catch((err) => {
  console.error("Migration failed:", err);
  process.exit(1);
});
