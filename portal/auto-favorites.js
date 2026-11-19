const MARKETS = [
  ["New York", 40.7128, -74.006],
  ["Los Angeles", 34.0522, -118.2437],
  ["Chicago", 41.8781, -87.6298],
  ["Philadelphia", 39.9526, -75.1652],
  ["Dallas", 32.7767, -96.797],
  ["Houston", 29.7604, -95.3698],
  ["Atlanta", 33.749, -84.388],
  ["Washington", 38.9072, -77.0369],
  ["Boston", 42.3601, -71.0589],
  ["Phoenix", 33.4484, -112.074],
  ["San Francisco", 37.7749, -122.4194],
  ["Seattle", 47.6062, -122.3321],
  ["Tampa", 27.9506, -82.4572],
  ["Minneapolis", 44.9778, -93.265],
  ["Detroit", 42.3314, -83.0458],
  ["Denver", 39.7392, -104.9903],
  ["Miami", 25.7617, -80.1918],
  ["Orlando", 28.5383, -81.3792],
  ["Cleveland", 41.4993, -81.6944],
  ["Sacramento", 38.5816, -121.4944],
  ["St. Louis", 38.627, -90.1994],
  ["Portland", 45.5152, -122.6784],
  ["Charlotte", 35.2271, -80.8431],
  ["Pittsburgh", 40.4406, -79.9959],
  ["Raleigh", 35.7796, -78.6382],
  ["Baltimore", 39.2904, -76.6122],
  ["Nashville", 36.1627, -86.7816],
  ["Salt Lake City", 40.7608, -111.891],
  ["San Antonio", 29.4241, -98.4936],
  ["San Diego", 32.7157, -117.1611],
  ["Indianapolis", 39.7684, -86.1581],
  ["Kansas City", 39.0997, -94.5786],
  ["Columbus", 39.9612, -82.9988],
  ["Milwaukee", 43.0389, -87.9065],
  ["Cincinnati", 39.1031, -84.512],
  ["Austin", 30.2672, -97.7431],
  ["Las Vegas", 36.1699, -115.1398],
  ["Jacksonville", 30.3322, -81.6557],
  ["Oklahoma City", 35.4676, -97.5164],
  ["Memphis", 35.1495, -90.049],
  ["Louisville", 38.2527, -85.7585],
  ["New Orleans", 29.9511, -90.0715],
  ["Buffalo", 42.8864, -78.8784],
  ["Birmingham", 33.5186, -86.8104],
  ["Hartford", 41.7658, -72.6734],
  ["Providence", 41.824, -71.4128],
  ["Honolulu", 21.3069, -157.8583],
  ["Anchorage", 61.2181, -149.9003]
].map(([name, lat, lon]) => ({ name, lat, lon }));

const LOCAL_NETWORKS = [
  ["ABC", "USA | Local ABC"],
  ["FOX", "USA | Local FOX"],
  ["NBC", "USA | Local NBC"],
  ["CBS", "USA | Local CBS"],
  ["CW", "USA | Local CW & MY"],
  ["PBS", "USA | Local PBS"]
];

const NATIONAL = [
  ["TNT", ""],
  ["FX East", "FX"],
  ["FXX", ""],
  ["HGTV East", "HGTV"],
  ["truTV East", "Tru TV"],
  ["E! East", "E!"]
];

const SPORTS_NETWORKS = [
  ["ESPN", ""],
  ["ESPN News", ""],
  ["ESPN U", ""],
  ["ESPN 2", ""],
  ["SEC Network", ""],
  ["ACC Network", ""],
  ["Big Ten Network", ""],
  ["CBS Sports Network", ""],
  ["FOX Sports 1", ""],
  ["FOX Sports 2", ""]
];

const PRO_TEAMS = {
  nfl: ["49ers", "Bears", "Bengals", "Bills", "Broncos", "Browns", "Buccaneers", "Cardinals", "Chargers", "Chiefs", "Colts", "Cowboys", "Dolphins", "Eagles", "Falcons", "Giants", "Jaguars", "Jets", "Lions", "Packers", "Panthers", "Patriots", "Raiders", "Rams", "Ravens", "Commanders", "Saints", "Seahawks", "Steelers", "Texans", "Titans", "Vikings"],
  nba: ["Hawks", "Celtics", "Nets", "Hornets", "Bulls", "Cavaliers", "Mavericks", "Nuggets", "Pistons", "Warriors", "Rockets", "Pacers", "Clippers", "Lakers", "Grizzlies", "Heat", "Bucks", "Timberwolves", "Pelicans", "Knicks", "Thunder", "Magic", "76ers", "Suns", "Trail Blazers", "Kings", "Spurs", "Raptors", "Jazz", "Wizards"],
  mlb: ["Diamondbacks", "Braves", "Orioles", "Red Sox", "Cubs", "White Sox", "Reds", "Guardians", "Rockies", "Tigers", "Astros", "Royals", "Angels", "Dodgers", "Marlins", "Brewers", "Twins", "Mets", "Yankees", "Athletics", "Phillies", "Pirates", "Padres", "Giants", "Mariners", "Cardinals", "Rays", "Rangers", "Blue Jays", "Nationals"],
  nhl: ["Ducks", "Bruins", "Sabres", "Flames", "Hurricanes", "Blackhawks", "Avalanche", "Blue Jackets", "Stars", "Red Wings", "Oilers", "Panthers", "Kings", "Wild", "Canadiens", "Predators", "Devils", "Islanders", "Rangers", "Senators", "Flyers", "Penguins", "Sharks", "Kraken", "Blues", "Lightning", "Maple Leafs", "Canucks", "Golden Knights", "Capitals", "Jets", "Mammoth"]
};

const SPORTS = [
  { id: "nfl", label: "NFL Football", groups: ["USA | NFL Teams", "USA | NFL Teams Backup"], teams: PRO_TEAMS.nfl },
  { id: "nba", label: "NBA Basketball", groups: ["USA | NBA Teams"], teams: PRO_TEAMS.nba },
  { id: "mlb", label: "MLB Baseball", groups: ["USA | MLB Teams"], teams: PRO_TEAMS.mlb },
  { id: "nhl", label: "NHL Hockey", groups: ["USA | NHL Teams"], teams: PRO_TEAMS.nhl }
];

const SLING_BOTH = [
  ["A&E East", "A&E"],
  ["AMC", ""],
  ["AXS TV", ""],
  ["BBC America", ""],
  ["BET East", "BET"],
  ["Bloomberg", ""],
  ["Cartoon Network", ""],
  ["Adult Swim", ""],
  ["Charge!", ""],
  ["CNN", ""],
  ["Comedy Central", ""],
  ["Comet", ""],
  ["Food Network East", "Food Network"],
  ["Freeform", ""],
  ["Fuse TV", "Fuse"],
  ["HGTV East", "HGTV"],
  ["History", ""],
  ["IFC", ""],
  ["Investigation Discovery", ""],
  ["Lifetime East", "Lifetime"],
  ["MGM+ Drive-In", ""],
  ["Nick Jr", "Nick Jr."],
  ["QVC", ""],
  ["TBS East", "TBS"],
  ["TNT", ""],
  ["Travel Channel", ""],
  ["Vice", "VICE"]
];

const SLING_ORANGE = [
  ["Discovery Turbo", ""],
  ["Disney Channel", ""],
  ["ESPN", ""],
  ["ESPN 2", ""],
  ["MeTV Network", "MeTV"],
  ["Plex: Motorvision TV", "MotorTrend"]
];

const SLING_BLUE = [
  ["Bravo East", "Bravo"],
  ["Discovery East", "Discovery"],
  ["E! East", "E!"],
  ["FOX News Channel", "Fox News"],
  ["FOX Sports 1", ""],
  ["FX East", "FX"],
  ["HLN", ""],
  ["MS NOW", ""],
  ["National Geographic Channel", "National Geographic"],
  ["NFL Network", ""],
  ["Syfy East", "Syfy"],
  ["TLC East", "TLC"],
  ["truTV East", "Tru TV"],
  ["USA Network", "USA Network"],
  ["Network", "USA Network"]
];

const GROUPS = [
  ...LOCAL_NETWORKS.map((entry) => entry[1]),
  "USA | Entertainment",
  "USA | Movies",
  "USA | Sports",
  "USA | Kids",
  "USA | News",
  "USA | Documentary",
  "USA | PlexTV",
  "USA | Music",
  ...SPORTS.flatMap((sport) => sport.groups)
];

const PACKAGES = {
  filo: {
    locals: [],
    channels: [
      ["A&E East", "A&E"], ["AMC", ""], ["Animal Planet East", "Animal Planet"], ["AXS TV", ""],
      ["BBC America", ""], ["BET East", "BET"], ["Bravo East", "Bravo"], ["CMT", ""],
      ["Comedy Central", ""], ["Cooking Channel", ""], ["Crime & Investigation Network", "Crime + Investigation"],
      ["Destination America", ""], ["Discovery East", "Discovery"], ["Discovery Family", ""],
      ["Discovery Life", ""], ["Discovery Science", "Science"], ["Discovery Turbo", ""],
      ["Food Network East", "Food Network"], ["FYI", ""], ["GSN", "Game Show Network"],
      ["Hallmark Channel East", "Hallmark"], ["Hallmark Family", ""], ["Hallmark Movies & Mysteries", ""],
      ["Heroes & Icons", ""], ["HGTV East", "HGTV"], ["HSN", ""], ["History", ""], ["IFC", ""],
      ["INSP", ""], ["Investigation Discovery", ""], ["Lifetime East", "Lifetime"],
      ["Lifetime Movie East", "LMN"], ["Logo East", "Logo"], ["Magnolia Network", ""],
      ["MeTV Network", "MeTV"], ["MeTV Toons", ""], ["MTV", ""], ["MTV Classic", ""], ["MTV Live", ""],
      ["MTV 2", "MTV2"], ["Nick Jr", "Nick Jr."], ["Nickelodeon", ""], ["Nicktoons", ""],
      ["OWN", ""], ["Paramount Network", ""], ["QVC", ""], ["Smithsonian Channel", ""],
      ["Start TV", ""], ["Sundance TV", ""], ["TeenNick", ""], ["TLC East", "TLC"],
      ["Travel Channel", ""], ["TV Land", ""], ["VH1", ""], ["Vice", "VICE"], ["We TV", "WE tv"]
    ]
  },
  utoob: {
    locals: ["ABC", "CBS", "FOX", "NBC", "CW", "PBS"],
    channels: [
      ["Adult Swim", ""], ["AMC", ""], ["Animal Planet East", "Animal Planet"], ["BBC America", ""],
      ["BET East", "BET"], ["Big Ten Network", ""], ["Bravo East", "Bravo"], ["Cartoon Network", ""],
      ["CBS Sports Network", ""], ["Charge!", ""], ["CMT", ""], ["CNBC", ""], ["CNN", ""],
      ["Comedy Central", ""], ["Court TV", ""], ["Discovery East", "Discovery"], ["Discovery Turbo", ""],
      ["Disney Channel", ""], ["Disney Junior", ""], ["Disney XD", ""], ["E! East", "E!"],
      ["ESPN", ""], ["ESPN 2", ""], ["ESPN News", ""], ["ESPN U", ""], ["Food Network East", "Food Network"],
      ["FOX News Channel", "Fox News"], ["Freeform", ""], ["FOX Sports 1", ""], ["FOX Sports 2", ""],
      ["FX East", "FX"], ["FXX", ""], ["GSN", "Game Show Network"], ["Hallmark Channel East", "Hallmark"],
      ["Hallmark Family", ""], ["Hallmark Movies & Mysteries", ""], ["HGTV East", "HGTV"], ["HLN", ""],
      ["HSN", ""], ["IFC", ""], ["Investigation Discovery", ""], ["ION Television", "ION"],
      ["Magnolia Network", ""], ["MS NOW", ""], ["MTV", ""], ["MTV 2", "MTV2"], ["MTV Classic", ""],
      ["Nat Geo Wild East", "Nat Geo Wild"], ["National Geographic Channel", "National Geographic"],
      ["NBA TV", ""], ["NBC News NOW", ""], ["NBCSN", "NBC Sports"], ["NFL Network", ""],
      ["Nick Jr", "Nick Jr."], ["Nickelodeon", ""], ["Nicktoons", ""], ["OWN", ""],
      ["Oxygen True Crime Network", "Oxygen"], ["Paramount Network", ""], ["QVC", ""],
      ["SEC Network", ""], ["Smithsonian Channel", ""], ["Start TV", ""], ["Sundance TV", ""],
      ["Syfy East", "Syfy"], ["TBS East", "TBS"], ["TCM", ""], ["TeenNick", ""], ["TLC East", "TLC"],
      ["TNT", ""], ["Travel Channel", ""], ["truTV East", "Tru TV"], ["TV Land", ""],
      ["USA Network", "USA Network"], ["Network", "USA Network"], ["VH1", ""], ["We TV", "WE tv"],
      ["Bloomberg Originals", ""], ["The Weather Channel", "Weather Channel"], ["ACC Network", ""]
    ]
  },
  fubo: {
    locals: ["ABC", "CBS", "FOX", "NBC", "CW"],
    channels: [
      ["ACC Network", ""], ["Big Ten Network", ""], ["BET East", "BET"], ["Bravo East", "Bravo"],
      ["CBS Sports Network", ""], ["Comedy Central", ""], ["Disney Channel", ""], ["Disney XD", ""],
      ["ESPN", ""], ["ESPN 2", ""], ["FOX News Channel", "Fox News"], ["FOX Sports 1", ""],
      ["FOX Sports 2", ""], ["FX East", "FX"], ["FXX", ""], ["MTV", ""], ["NBC News NOW", ""],
      ["NBCSN", "NBC Sports"], ["NFL Network", ""], ["Nickelodeon", ""], ["SEC Network", ""],
      ["NBA TV", ""], ["National Geographic Channel", "National Geographic"],
      ["USA Network", "USA Network"], ["Network", "USA Network"], ["E! East", "E!"]
    ]
  },
  directv: {
    locals: ["ABC", "CBS", "FOX", "NBC", "CW"],
    channels: [
      ["BET East", "BET"], ["Bravo East", "Bravo"], ["Cartoon Network", ""], ["CNBC", ""],
      ["CNN", ""], ["Comedy Central", ""], ["Discovery East", "Discovery"], ["Discovery Turbo", ""],
      ["Disney Channel", ""], ["Disney Junior", ""], ["Disney XD", ""], ["ESPN", ""], ["ESPN 2", ""],
      ["Food Network East", "Food Network"], ["FOX News Channel", "Fox News"], ["Freeform", ""],
      ["FX East", "FX"], ["Hallmark Channel East", "Hallmark"], ["Hallmark Movies & Mysteries", ""],
      ["HGTV East", "HGTV"], ["ION Television", "ION"], ["MTV 2", "MTV2"],
      ["National Geographic Channel", "National Geographic"], ["NBC News NOW", ""],
      ["Paramount Network", ""], ["TBS East", "TBS"],
      ["USA Network", "USA Network"], ["Network", "USA Network"]
    ]
  }
};

function bare(name) {
  return String(name || "").replace(/^\s*US:\s*/i, "").trim().toLowerCase();
}

function hasWord(name, team) {
  const escaped = String(team).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp("(^|[^a-z0-9])" + escaped + "([^a-z0-9]|$)", "i").test(String(name || ""));
}

function haversine(lat1, lon1, lat2, lon2) {
  const scale = Math.PI / 180;
  const halfLat = ((lat2 - lat1) * scale) / 2;
  const halfLon = ((lon2 - lon1) * scale) / 2;
  const a = Math.sin(halfLat) ** 2 + Math.cos(lat1 * scale) * Math.cos(lat2 * scale) * Math.sin(halfLon) ** 2;
  return 6371 * 2 * Math.asin(Math.min(1, Math.sqrt(a)));
}

function nearestMarket(lat, lon) {
  let best = MARKETS[0];
  let bestDistance = Infinity;
  for (const market of MARKETS) {
    const distance = haversine(lat, lon, market.lat, market.lon);
    if (distance < bestDistance) {
      best = market;
      bestDistance = distance;
    }
  }
  return best;
}

function splitLocal(name) {
  const parts = String(name || "").split("|").map((part) => part.trim());
  if (parts.length < 3) return null;
  return { city: parts[1], network: (parts[2].split(/\s+/)[0] || "").toUpperCase() };
}

function pickLocal(channels, marketName, networks) {
  const want = String(marketName || "").toLowerCase();
  const allowed = networks ? new Set(networks) : null;
  const picks = [];
  for (const [network, group] of LOCAL_NETWORKS) {
    if (allowed && !allowed.has(network)) continue;
    const hits = channels.filter((channel) => {
      if (channel.group !== group) return false;
      const parsed = splitLocal(channel.name);
      return parsed && parsed.city.toLowerCase() === want && parsed.network === network;
    });
    hits.sort((left, right) => {
      const leftShout = left.name === left.name.toUpperCase() ? 1 : 0;
      const rightShout = right.name === right.name.toUpperCase() ? 1 : 0;
      return leftShout - rightShout || left.name.length - right.name.length;
    });
    if (hits[0]) picks.push({ id: String(hits[0].id), name: hits[0].name, label: "" });
  }
  return picks;
}

function pickNamed(channels, specs) {
  const picks = [];
  for (const [target, label] of specs) {
    const want = target.toLowerCase();
    let exact = null;
    let fallback = null;
    for (const channel of channels) {
      if (!String(channel.group || "").startsWith("USA |")) continue;
      const name = bare(channel.name);
      if (name === want) {
        exact = channel;
        break;
      }
      if (!fallback && name === want + " hd") fallback = channel;
    }
    const chosen = exact || fallback;
    if (chosen) picks.push({ id: String(chosen.id), name: chosen.name, label: label || "" });
  }
  return picks;
}

function localPicks(channels, lat, lon) {
  const market = nearestMarket(Number(lat), Number(lon));
  return {
    market: market.name,
    picks: pickLocal(channels, market.name).concat(pickNamed(channels, NATIONAL))
  };
}

function sportsPicks(channels) {
  return pickNamed(channels, SPORTS_NETWORKS);
}

function nationalPicks(channels) {
  return pickNamed(channels, NATIONAL);
}

function uniquePicks(picks) {
  const seen = new Set();
  return picks.filter((pick) => {
    if (seen.has(pick.id)) return false;
    seen.add(pick.id);
    return true;
  });
}

function packagePicks(id, channels, lat, lon) {
  const pack = PACKAGES[id];
  if (!pack) return { market: "", picks: [] };
  const picks = pickNamed(channels, pack.channels);
  let market = "";
  if (pack.locals.length && Number.isFinite(Number(lat)) && Number.isFinite(Number(lon))) {
    const found = nearestMarket(Number(lat), Number(lon));
    market = found.name;
    picks.unshift(...pickLocal(channels, found.name, pack.locals));
  }
  return { market, picks: uniquePicks(picks) };
}

function slingPicks(which, channels, lat, lon) {
  const specs = which === "orange" ? SLING_BOTH.concat(SLING_ORANGE) : SLING_BOTH.concat(SLING_BLUE);
  const picks = pickNamed(channels, specs);
  let market = "";
  if (Number.isFinite(Number(lat)) && Number.isFinite(Number(lon))) {
    const located = localPicks(channels, lat, lon);
    market = located.market;
    picks.unshift(...located.picks);
  }
  return { market, picks: uniquePicks(picks) };
}

function sportById(id) {
  return SPORTS.find((sport) => sport.id === id) || null;
}

function cleanSide(value) {
  const text = String(value || "").replace(/^#\d+\s+/, "").replace(/\s+/g, " ").trim();
  if (text.length < 2 || text.length > 42) return "";
  if (/^no event$/i.test(text) || /^\d+$/.test(text)) return "";
  return text;
}

function sides(name) {
  let rest = String(name || "");
  const colon = rest.indexOf(":");
  if (colon >= 0) rest = rest.slice(colon + 1);
  rest = rest.split("@")[0];
  const match = rest.match(/(.+?)\s+(?:vs\.?|at)\s+(.+)/i);
  if (!match) return [];
  return [cleanSide(match[1]), cleanSide(match[2])].filter(Boolean);
}

function inGroups(channels, groups) {
  const wanted = new Set(groups);
  return channels.filter((channel) => wanted.has(channel.group));
}

function teamScore(name, team) {
  const text = String(name || "").trim();
  if (!text || /radio\s*:/i.test(text)) return -1;
  if (!hasWord(text, team)) return -1;
  let score = 50;
  if (/\([A-Z]{3,5}\)/.test(text)) score -= 40;
  if (/teams\s*:/i.test(text)) score -= 35;
  const stripped = text.replace(/^(?:US:\s*)?(?:NFL|NBA|MLB|NHL)\s+/i, "").trim();
  const words = stripped.split(/\s+/).filter(Boolean);
  const last = (words[words.length - 1] || "").toLowerCase();
  if (last === team.toLowerCase() && words.length <= 3) score += 40;
  if (stripped.toLowerCase() === team.toLowerCase()) score += 20;
  return score - Math.min(text.length, 80) / 10;
}

function bestTeamChannel(pool, team) {
  let best = null;
  let bestScore = -1;
  for (const channel of pool) {
    const score = teamScore(channel.name, team);
    if (score > bestScore) {
      best = channel;
      bestScore = score;
    }
  }
  return best;
}

function slotLabel(name) {
  const text = String(name || "").trim();
  const teams = sides(text);
  if (teams.length === 2) return teams.join(" vs ");
  const head = text.split(":")[0].replace(/\s+/g, " ").trim();
  if (head && !/^no event$/i.test(head)) return head;
  return text.replace(/:\s*no event\s*$/i, "").trim() || text;
}

function slotNumber(name) {
  const match = String(name || "").match(/(\d+)/);
  return match ? Number(match[1]) : 9999;
}

function teamsFor(sportId, channels) {
  const sport = sportById(sportId);
  if (!sport) return [];
  const pool = inGroups(channels, sport.groups);
  if (!sport.teams.length) {
    return pool
      .map((channel) => ({ name: slotLabel(channel.name), id: String(channel.id) }))
      .sort((left, right) => slotNumber(left.name) - slotNumber(right.name) || left.name.localeCompare(right.name));
  }
  const found = [];
  for (const team of sport.teams) {
    const hit = bestTeamChannel(pool, team);
    if (!hit) continue;
    found.push({ name: team.replace(/\b\w/g, (letter) => letter.toUpperCase()), id: String(hit.id) });
  }
  return found.sort((left, right) => left.name.localeCompare(right.name));
}

function picksForTeams(sportId, channels, names) {
  const sport = sportById(sportId);
  const wanted = new Set((names || []).map((name) => String(name || "").trim().toLowerCase()).filter(Boolean));
  const picks = [];
  const seen = new Set();
  const labelTeam = Boolean(sport && sport.teams.length);
  for (const team of teamsFor(sportId, channels)) {
    if (!wanted.has(team.name.toLowerCase()) || seen.has(team.id)) continue;
    seen.add(team.id);
    picks.push({ id: team.id, name: team.name, label: labelTeam ? team.name : "" });
  }
  return picks;
}

module.exports = {
  GROUPS,
  SPORTS: SPORTS.map((sport) => ({ id: sport.id, label: sport.label })),
  nearestMarket,
  localPicks,
  nationalPicks,
  sportsPicks,
  slingPicks,
  packagePicks,
  teamsFor,
  picksForTeams
};
