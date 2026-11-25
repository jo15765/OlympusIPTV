package com.olympus.iptv.data

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object AutoFavorites {
    data class GuideChannel(val id: String, val name: String, val group: String)
    data class ChannelPick(val id: String, val name: String, val label: String = "")
    data class TeamChoice(val name: String, val id: String)
    data class Sport(val id: String, val label: String)
    data class LocalResult(val market: String, val picks: List<ChannelPick>)

    private data class Market(val name: String, val lat: Double, val lon: Double)
    private data class League(val id: String, val label: String, val groups: List<String>, val teams: List<String>)

    private val markets = listOf(
        Market("New York", 40.7128, -74.0060),
        Market("Los Angeles", 34.0522, -118.2437),
        Market("Chicago", 41.8781, -87.6298),
        Market("Philadelphia", 39.9526, -75.1652),
        Market("Dallas", 32.7767, -96.7970),
        Market("Houston", 29.7604, -95.3698),
        Market("Atlanta", 33.7490, -84.3880),
        Market("Athens", 33.9519, -83.3576),
        Market("Augusta", 33.4735, -82.0105),
        Market("Macon", 32.8407, -83.6324),
        Market("Savannah", 32.0809, -81.0912),
        Market("Columbus GA", 32.4610, -84.9877),
        Market("Washington", 38.9072, -77.0369),
        Market("Boston", 42.3601, -71.0589),
        Market("Phoenix", 33.4484, -112.0740),
        Market("San Francisco", 37.7749, -122.4194),
        Market("Seattle", 47.6062, -122.3321),
        Market("Tampa", 27.9506, -82.4572),
        Market("Minneapolis", 44.9778, -93.2650),
        Market("Detroit", 42.3314, -83.0458),
        Market("Denver", 39.7392, -104.9903),
        Market("Miami", 25.7617, -80.1918),
        Market("Orlando", 28.5383, -81.3792),
        Market("Cleveland", 41.4993, -81.6944),
        Market("Sacramento", 38.5816, -121.4944),
        Market("St. Louis", 38.6270, -90.1994),
        Market("Portland", 45.5152, -122.6784),
        Market("Charlotte", 35.2271, -80.8431),
        Market("Pittsburgh", 40.4406, -79.9959),
        Market("Raleigh", 35.7796, -78.6382),
        Market("Baltimore", 39.2904, -76.6122),
        Market("Nashville", 36.1627, -86.7816),
        Market("Salt Lake City", 40.7608, -111.8910),
        Market("San Antonio", 29.4241, -98.4936),
        Market("San Diego", 32.7157, -117.1611),
        Market("Indianapolis", 39.7684, -86.1581),
        Market("Kansas City", 39.0997, -94.5786),
        Market("Columbus", 39.9612, -82.9988),
        Market("Milwaukee", 43.0389, -87.9065),
        Market("Cincinnati", 39.1031, -84.5120),
        Market("Austin", 30.2672, -97.7431),
        Market("Las Vegas", 36.1699, -115.1398),
        Market("Jacksonville", 30.3322, -81.6557),
        Market("Oklahoma City", 35.4676, -97.5164),
        Market("Memphis", 35.1495, -90.0490),
        Market("Louisville", 38.2527, -85.7585),
        Market("New Orleans", 29.9511, -90.0715),
        Market("Buffalo", 42.8864, -78.8784),
        Market("Birmingham", 33.5186, -86.8104),
        Market("Hartford", 41.7658, -72.6734),
        Market("Providence", 41.8240, -71.4128),
        Market("Honolulu", 21.3069, -157.8583),
        Market("Anchorage", 61.2181, -149.9003)
    )

    private val localNetworks = listOf(
        "ABC" to "USA | Local ABC",
        "FOX" to "USA | Local FOX",
        "NBC" to "USA | Local NBC",
        "CBS" to "USA | Local CBS",
        "CW" to "USA | Local CW & MY",
        "PBS" to "USA | Local PBS"
    )

    private val national = listOf(
        "TNT" to "",
        "FX East" to "FX",
        "FXX" to "",
        "HGTV East" to "HGTV",
        "truTV East" to "Tru TV",
        "E! East" to "E!"
    )

    private val sportsNetworks = listOf(
        "ESPN" to "",
        "ESPN News" to "",
        "ESPN U" to "",
        "ESPN 2" to "",
        "SEC Network" to "",
        "ACC Network" to "",
        "Big Ten Network" to "",
        "CBS Sports Network" to "",
        "FOX Sports 1" to "",
        "FOX Sports 2" to ""
    )

    private val leagues = listOf(
        League("nfl", "NFL Football", listOf("USA | NFL Teams", "USA | NFL Teams Backup"), listOf("49ers", "Bears", "Bengals", "Bills", "Broncos", "Browns", "Buccaneers", "Cardinals", "Chargers", "Chiefs", "Colts", "Cowboys", "Dolphins", "Eagles", "Falcons", "Giants", "Jaguars", "Jets", "Lions", "Packers", "Panthers", "Patriots", "Raiders", "Rams", "Ravens", "Commanders", "Saints", "Seahawks", "Steelers", "Texans", "Titans", "Vikings")),
        League("nba", "NBA Basketball", listOf("USA | NBA Teams"), listOf("Hawks", "Celtics", "Nets", "Hornets", "Bulls", "Cavaliers", "Mavericks", "Nuggets", "Pistons", "Warriors", "Rockets", "Pacers", "Clippers", "Lakers", "Grizzlies", "Heat", "Bucks", "Timberwolves", "Pelicans", "Knicks", "Thunder", "Magic", "76ers", "Suns", "Trail Blazers", "Kings", "Spurs", "Raptors", "Jazz", "Wizards")),
        League("mlb", "MLB Baseball", listOf("USA | MLB Teams"), listOf("Diamondbacks", "Braves", "Orioles", "Red Sox", "Cubs", "White Sox", "Reds", "Guardians", "Rockies", "Tigers", "Astros", "Royals", "Angels", "Dodgers", "Marlins", "Brewers", "Twins", "Mets", "Yankees", "Athletics", "Phillies", "Pirates", "Padres", "Giants", "Mariners", "Cardinals", "Rays", "Rangers", "Blue Jays", "Nationals")),
        League("nhl", "NHL Hockey", listOf("USA | NHL Teams"), listOf("Ducks", "Bruins", "Sabres", "Flames", "Hurricanes", "Blackhawks", "Avalanche", "Blue Jackets", "Stars", "Red Wings", "Oilers", "Panthers", "Kings", "Wild", "Canadiens", "Predators", "Devils", "Islanders", "Rangers", "Senators", "Flyers", "Penguins", "Sharks", "Kraken", "Blues", "Lightning", "Maple Leafs", "Canucks", "Golden Knights", "Capitals", "Jets", "Mammoth"))
    )

    private val slingBoth = listOf(
        "A&E East" to "A&E",
        "AMC" to "",
        "AXS TV" to "",
        "BBC America" to "",
        "BET East" to "BET",
        "Bloomberg" to "",
        "Cartoon Network" to "",
        "Adult Swim" to "",
        "Charge!" to "",
        "CNN" to "",
        "Comedy Central" to "",
        "Comet" to "",
        "Food Network East" to "Food Network",
        "Freeform" to "",
        "Fuse TV" to "Fuse",
        "HGTV East" to "HGTV",
        "History" to "",
        "IFC" to "",
        "Investigation Discovery" to "",
        "Lifetime East" to "Lifetime",
        "MGM+ Drive-In" to "",
        "Nick Jr" to "Nick Jr.",
        "QVC" to "",
        "TBS East" to "TBS",
        "TNT" to "",
        "Travel Channel" to "",
        "Vice" to "VICE"
    )

    private val slingOrange = listOf(
        "Discovery Turbo" to "",
        "Disney Channel" to "",
        "ESPN" to "",
        "ESPN 2" to "",
        "MeTV Network" to "MeTV",
        "Plex: Motorvision TV" to "MotorTrend"
    )

    private val slingBlue = listOf(
        "Bravo East" to "Bravo",
        "Discovery East" to "Discovery",
        "E! East" to "E!",
        "FOX News Channel" to "Fox News",
        "FOX Sports 1" to "",
        "FX East" to "FX",
        "HLN" to "",
        "MS NOW" to "",
        "National Geographic Channel" to "National Geographic",
        "NFL Network" to "",
        "Syfy East" to "Syfy",
        "TLC East" to "TLC",
        "truTV East" to "Tru TV",
        "USA Network" to "USA Network",
        "Network" to "USA Network"
    )

    private val packages = mapOf(
        "filo" to (emptySet<String>() to listOf(
            "A&E East" to "A&E", "AMC" to "", "Animal Planet East" to "Animal Planet", "AXS TV" to "",
            "BBC America" to "", "BET East" to "BET", "Bravo East" to "Bravo", "CMT" to "",
            "Comedy Central" to "", "Cooking Channel" to "", "Crime & Investigation Network" to "Crime + Investigation",
            "Destination America" to "", "Discovery East" to "Discovery", "Discovery Family" to "",
            "Discovery Life" to "", "Discovery Science" to "Science", "Discovery Turbo" to "",
            "Food Network East" to "Food Network", "FYI" to "", "GSN" to "Game Show Network",
            "Hallmark Channel East" to "Hallmark", "Hallmark Family" to "", "Hallmark Movies & Mysteries" to "",
            "Heroes & Icons" to "", "HGTV East" to "HGTV", "HSN" to "", "History" to "", "IFC" to "",
            "INSP" to "", "Investigation Discovery" to "", "Lifetime East" to "Lifetime",
            "Lifetime Movie East" to "LMN", "Logo East" to "Logo", "Magnolia Network" to "",
            "MeTV Network" to "MeTV", "MeTV Toons" to "", "MTV" to "", "MTV Classic" to "", "MTV Live" to "",
            "MTV 2" to "MTV2", "Nick Jr" to "Nick Jr.", "Nickelodeon" to "", "Nicktoons" to "",
            "OWN" to "", "Paramount Network" to "", "QVC" to "", "Smithsonian Channel" to "",
            "Start TV" to "", "Sundance TV" to "", "TeenNick" to "", "TLC East" to "TLC",
            "Travel Channel" to "", "TV Land" to "", "VH1" to "", "Vice" to "VICE", "We TV" to "WE tv"
        )),
        "utoob" to (setOf("ABC", "CBS", "FOX", "NBC", "CW", "PBS") to listOf(
            "Adult Swim" to "", "AMC" to "", "Animal Planet East" to "Animal Planet", "BBC America" to "",
            "BET East" to "BET", "Big Ten Network" to "", "Bravo East" to "Bravo", "Cartoon Network" to "",
            "CBS Sports Network" to "", "Charge!" to "", "CMT" to "", "CNBC" to "", "CNN" to "",
            "Comedy Central" to "", "Court TV" to "", "Discovery East" to "Discovery", "Discovery Turbo" to "",
            "Disney Channel" to "", "Disney Junior" to "", "Disney XD" to "", "E! East" to "E!",
            "ESPN" to "", "ESPN 2" to "", "ESPN News" to "", "ESPN U" to "", "Food Network East" to "Food Network",
            "FOX News Channel" to "Fox News", "Freeform" to "", "FOX Sports 1" to "", "FOX Sports 2" to "",
            "FX East" to "FX", "FXX" to "", "GSN" to "Game Show Network", "Hallmark Channel East" to "Hallmark",
            "Hallmark Family" to "", "Hallmark Movies & Mysteries" to "", "HGTV East" to "HGTV", "HLN" to "",
            "HSN" to "", "IFC" to "", "Investigation Discovery" to "", "ION Television" to "ION",
            "Magnolia Network" to "", "MS NOW" to "", "MTV" to "", "MTV 2" to "MTV2", "MTV Classic" to "",
            "Nat Geo Wild East" to "Nat Geo Wild", "National Geographic Channel" to "National Geographic",
            "NBA TV" to "", "NBC News NOW" to "", "NBCSN" to "NBC Sports", "NFL Network" to "",
            "Nick Jr" to "Nick Jr.", "Nickelodeon" to "", "Nicktoons" to "", "OWN" to "",
            "Oxygen True Crime Network" to "Oxygen", "Paramount Network" to "", "QVC" to "",
            "SEC Network" to "", "Smithsonian Channel" to "", "Start TV" to "", "Sundance TV" to "",
            "Syfy East" to "Syfy", "TBS East" to "TBS", "TCM" to "", "TeenNick" to "", "TLC East" to "TLC",
            "TNT" to "", "Travel Channel" to "", "truTV East" to "Tru TV", "TV Land" to "",
            "USA Network" to "USA Network", "Network" to "USA Network", "VH1" to "", "We TV" to "WE tv",
            "Bloomberg Originals" to "", "The Weather Channel" to "Weather Channel", "ACC Network" to ""
        )),
        "fubo" to (setOf("ABC", "CBS", "FOX", "NBC", "CW") to listOf(
            "ACC Network" to "", "Big Ten Network" to "", "BET East" to "BET", "Bravo East" to "Bravo",
            "CBS Sports Network" to "", "Comedy Central" to "", "Disney Channel" to "", "Disney XD" to "",
            "ESPN" to "", "ESPN 2" to "", "FOX News Channel" to "Fox News", "FOX Sports 1" to "",
            "FOX Sports 2" to "", "FX East" to "FX", "FXX" to "", "MTV" to "", "NBC News NOW" to "",
            "NBCSN" to "NBC Sports", "NFL Network" to "", "Nickelodeon" to "", "SEC Network" to "",
            "NBA TV" to "", "National Geographic Channel" to "National Geographic",
            "USA Network" to "USA Network", "Network" to "USA Network", "E! East" to "E!"
        )),
        "directv" to (setOf("ABC", "CBS", "FOX", "NBC", "CW") to listOf(
            "BET East" to "BET", "Bravo East" to "Bravo", "Cartoon Network" to "", "CNBC" to "",
            "CNN" to "", "Comedy Central" to "", "Discovery East" to "Discovery", "Discovery Turbo" to "",
            "Disney Channel" to "", "Disney Junior" to "", "Disney XD" to "", "ESPN" to "", "ESPN 2" to "",
            "Food Network East" to "Food Network", "FOX News Channel" to "Fox News", "Freeform" to "",
            "FX East" to "FX", "Hallmark Channel East" to "Hallmark", "Hallmark Movies & Mysteries" to "",
            "HGTV East" to "HGTV", "ION Television" to "ION", "MTV 2" to "MTV2",
            "National Geographic Channel" to "National Geographic", "NBC News NOW" to "",
            "Paramount Network" to "", "TBS East" to "TBS",
            "USA Network" to "USA Network", "Network" to "USA Network"
        )),
        "frndly" to (emptySet<String>() to listOf(
            "A&E East" to "A&E", "Crime & Investigation Network" to "Crime + Investigation",
            "Court TV" to "", "FYI" to "", "GSN" to "Game Show Network",
            "Hallmark Channel East" to "Hallmark", "Hallmark Family" to "",
            "Hallmark Movies & Mysteries" to "Hallmark Mystery", "Heroes & Icons" to "",
            "History" to "", "INSP" to "", "ION Television" to "ION",
            "Lifetime East" to "Lifetime", "Lifetime Movie East" to "LMN",
            "MeTV Network" to "MeTV", "MeTV Toons" to "", "Outdoor Channel" to "",
            "QVC" to "", "Start TV" to "", "The Weather Channel" to "Weather Channel",
            "Vice" to "VICE", "Aspire" to "", "Bounce" to "", "BYUtv" to "",
            "Catchy Comedy" to "", "Cleo TV" to "", "Dabl" to "", "FETV" to "",
            "getTV" to "", "Great American Family" to "", "Great American Living" to "",
            "Grit" to "", "Ion Mystery" to "", "Ion Plus" to "", "Justice Central" to "",
            "Laff" to "", "Local Now" to "", "MeTV+" to "", "Military History" to "",
            "Movies!" to "", "PixL" to "", "Pursuit Channel" to "", "Reelz" to "",
            "Story Television" to "", "Tastemade" to "", "TheGrio" to "", "UPtv" to "",
            "FMC" to ""
        )),
        "hulu" to (setOf("ABC", "CBS", "FOX", "NBC", "CW", "PBS") to listOf(
            "A&E East" to "A&E", "ABC News Live" to "", "ACC Network" to "", "Adult Swim" to "",
            "Animal Planet East" to "Animal Planet", "BET East" to "BET", "Big Ten Network" to "",
            "Bloomberg" to "", "Bravo East" to "Bravo", "Cartoon Network" to "",
            "CBS Sports Network" to "", "Charge!" to "", "CMT" to "", "CNBC" to "", "CNN" to "",
            "CNN International" to "", "C-SPAN" to "", "C-SPAN 2" to "", "C-SPAN 3" to "",
            "Comedy Central" to "", "Comedy.tv" to "", "Comet" to "", "Cozi TV" to "",
            "Crime & Investigation Network" to "Crime + Investigation", "Dabl" to "",
            "Discovery East" to "Discovery", "Discovery Turbo" to "", "Disney Channel" to "",
            "Disney Junior" to "", "Disney XD" to "", "E! East" to "E!", "ESPN" to "",
            "ESPN 2" to "", "ESPN News" to "", "ESPN U" to "", "FanDuel TV" to "",
            "Food Network East" to "Food Network", "FOX Business" to "", "FOX News Channel" to "Fox News",
            "FOX Soul" to "", "Fox Weather" to "", "Freeform" to "", "FOX Sports 1" to "",
            "FOX Sports 2" to "", "FX East" to "FX", "FXM" to "", "FXX" to "", "FYI" to "",
            "Galavision" to "", "GSN" to "Game Show Network", "Golf Channel" to "",
            "Great American Family" to "", "Hallmark Channel East" to "Hallmark",
            "Hallmark Movies & Mysteries" to "Hallmark Mystery", "HGTV East" to "HGTV",
            "History" to "", "HLN" to "", "Investigation Discovery" to "", "Justice Central" to "",
            "LiveNOW from FOX" to "", "Magnolia Network" to "", "MLB Network" to "",
            "MotorTrend" to "", "MS NOW" to "", "MTV" to "", "Nat Geo Wild East" to "Nat Geo Wild",
            "National Geographic Channel" to "National Geographic", "NBC News NOW" to "",
            "Newsmax" to "", "NewsNation" to "", "NFL Network" to "", "Nick Jr" to "Nick Jr.",
            "Nickelodeon" to "", "OWN" to "", "Oxygen True Crime Network" to "Oxygen",
            "Paramount Network" to "", "PBS Kids" to "", "Pop" to "", "QVC" to "",
            "SEC Network" to "", "Smithsonian Channel" to "", "Start TV" to "", "Syfy East" to "Syfy",
            "TBS East" to "TBS", "TCM" to "", "TheGrio" to "", "The Weather Channel" to "Weather Channel",
            "TLC East" to "TLC", "TNT" to "", "Travel Channel" to "", "True Crime Network" to "",
            "truTV East" to "Tru TV", "TUDN" to "", "TV Land" to "",
            "USA Network" to "USA Network", "Network" to "USA Network", "VH1" to "", "Vice" to "VICE"
        ))
    )

    val groups: List<String> = localNetworks.map { it.second } +
        listOf(
            "USA | Entertainment",
            "USA | Movies",
            "USA | Sports",
            "USA | Kids",
            "USA | News",
            "USA | Documentary",
            "USA | PlexTV",
            "USA | Music"
        ) +
        leagues.flatMap { it.groups }

    val sports: List<Sport> = leagues.map { Sport(it.id, it.label) }

    fun localPicks(channels: List<GuideChannel>, lat: Double, lon: Double): LocalResult {
        val located = localStations(channels, lat, lon)
        return LocalResult(located.market, located.picks + pickNamed(channels, national))
    }

    fun localStations(channels: List<GuideChannel>, lat: Double, lon: Double): LocalResult =
        bestLocal(channels, lat, lon, null)

    fun wantsLocalStations(id: String): Boolean = packages[id]?.first?.isNotEmpty() == true

    fun nationalPicks(channels: List<GuideChannel>): List<ChannelPick> = pickNamed(channels, national)

    fun sportsPicks(channels: List<GuideChannel>): List<ChannelPick> = pickNamed(channels, sportsNetworks)

    fun slingPicks(orange: Boolean, channels: List<GuideChannel>, lat: Double?, lon: Double?): LocalResult {
        val specs = if (orange) slingBoth + slingOrange else slingBoth + slingBlue
        val picks = pickNamed(channels, specs).toMutableList()
        val market = if (lat != null && lon != null) {
            val located = localPicks(channels, lat, lon)
            picks.addAll(0, located.picks)
            located.market
        } else {
            ""
        }
        val seen = HashSet<String>()
        return LocalResult(market, picks.filter { seen.add(it.id) })
    }

    fun packagePicks(id: String, channels: List<GuideChannel>, lat: Double?, lon: Double?): LocalResult {
        val pack = packages[id] ?: return LocalResult("", emptyList())
        val picks = pickNamed(channels, pack.second).toMutableList()
        val market = if (pack.first.isNotEmpty() && lat != null && lon != null) {
            val local = bestLocal(channels, lat, lon, pack.first.toSet())
            if (local.picks.isNotEmpty()) picks.addAll(0, local.picks)
            local.market
        } else {
            ""
        }
        val seen = HashSet<String>()
        return LocalResult(market, picks.filter { seen.add(it.id) })
    }

    fun teamsFor(sportId: String, channels: List<GuideChannel>): List<TeamChoice> {
        val sport = leagues.firstOrNull { it.id == sportId } ?: return emptyList()
        val pool = channels.filter { it.group in sport.groups }
        if (sport.teams.isEmpty()) {
            return pool
                .map { TeamChoice(slotLabel(it.name), it.id) }
                .sortedWith(compareBy<TeamChoice> { slotNumber(it.name) }.thenBy { it.name.lowercase() })
        }
        val found = ArrayList<TeamChoice>()
        for (team in sport.teams) {
            val hit = bestTeamChannel(pool, team) ?: continue
            found.add(TeamChoice(titleTeam(team), hit.id))
        }
        return found.sortedBy { it.name.lowercase() }
    }

    fun picksForTeams(sportId: String, channels: List<GuideChannel>, names: Collection<String>): List<ChannelPick> {
        val sport = leagues.firstOrNull { it.id == sportId }
        val wanted = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val seen = HashSet<String>()
        val picks = ArrayList<ChannelPick>()
        val labelTeam = sport != null && sport.teams.isNotEmpty()
        for (team in teamsFor(sportId, channels)) {
            if (team.name.lowercase() !in wanted || !seen.add(team.id)) continue
            picks.add(ChannelPick(team.id, team.name, if (labelTeam) team.name else ""))
        }
        return picks
    }

    private fun nearestMarket(lat: Double, lon: Double): Market {
        var best = markets.first()
        var bestDistance = Double.POSITIVE_INFINITY
        for (market in markets) {
            val distance = haversine(lat, lon, market.lat, market.lon)
            if (distance < bestDistance) {
                best = market
                bestDistance = distance
            }
        }
        return best
    }

    private val nonLocalSuffix = Regex("""\b(news|sports?|business|weather|soul|life|kids|create|world|action|movies?|golazo|now|live|deportes|espanol|spanish|plus|extra|24/7)\b""", RegexOption.IGNORE_CASE)
    private val knownLocalNetworks = setOf("ABC", "CBS", "NBC", "FOX", "CW", "PBS", "ION", "MY")
    private val networkWordRegex: Map<String, Regex> = knownLocalNetworks.associateWith { net ->
        Regex("""\b${Regex.escape(net)}\b""")
    }
    private val shortAliasRegex = HashMap<String, Regex>()
    private val wordSplit = Regex("""[^a-z0-9]+""")
    private val usPrefixRegex = Regex("^US:\\s*", RegexOption.IGNORE_CASE)
    private val digitsRegex = Regex("(\\d+)")
    private val cityAliasesMap = mapOf(
        "new york" to listOf("new york", "nyc", "manhattan"),
        "los angeles" to listOf("los angeles", "la "),
        "san francisco" to listOf("san francisco", "sf ", "bay area", "oakland", "san jose"),
        "dallas" to listOf("dallas", "fort worth", "dfw"),
        "washington" to listOf("washington", "d.c.", " dc"),
        "minneapolis" to listOf("minneapolis", "st paul", "st. paul", "saint paul", "twin cities"),
        "philadelphia" to listOf("philadelphia", "philly"),
        "st. louis" to listOf("st. louis", "st louis", "saint louis"),
        "st louis" to listOf("st. louis", "st louis", "saint louis"),
        "las vegas" to listOf("las vegas", "vegas"),
        "tampa" to listOf("tampa", "st petersburg", "st. petersburg", "tampa bay"),
        "miami" to listOf("miami", "fort lauderdale", "ft lauderdale"),
        "raleigh" to listOf("raleigh", "durham"),
        "norfolk" to listOf("norfolk", "hampton", "virginia beach"),
        "greensboro" to listOf("greensboro", "winston", "high point"),
        "greenville" to listOf("greenville", "spartanburg", "asheville"),
        "hartford" to listOf("hartford", "new haven"),
        "orlando" to listOf("orlando", "daytona"),
        "sacramento" to listOf("sacramento", "stockton"),
        "salt lake city" to listOf("salt lake", "slc")
    )

    private fun localNetworkIn(name: String, group: String): String? {
        val upper = name.uppercase()
        val tokens = wordSplit.split(name.lowercase())
        var found: String? = null
        for (t in tokens) {
            if (t.length !in 2..3) continue
            val u = t.uppercase()
            if (u in knownLocalNetworks) {
                found = u
                break
            }
        }
        if (found == null) {
            val gUpper = group.uppercase()
            for ((net, _) in localNetworks) {
                if (networkWordRegex[net]?.containsMatchIn(gUpper) == true) {
                    found = net
                    break
                }
            }
        }
        if (found == null) return null
        if (nonLocalSuffix.containsMatchIn(name) && !upper.contains("LOCAL")) return null
        return found
    }

    private fun aliasesForCity(marketName: String): List<String> {
        val base = cityKey(marketName)
        return cityAliasesMap[base] ?: listOf(base)
    }

    private fun matchesMarketCity(channel: GuideChannel, marketName: String): Boolean {
        val hay = "${channel.name} ${channel.group}".lowercase()
        val aliases = aliasesForCity(marketName)
        for (alias in aliases) {
            val clean = alias.trim()
            if (clean.length < 2) continue
            if (clean.length <= 3) {
                val rx = synchronized(shortAliasRegex) {
                    shortAliasRegex.getOrPut(clean) {
                        Regex("""(^|[^a-z0-9])${Regex.escape(clean)}([^a-z0-9]|$)""")
                    }
                }
                if (rx.containsMatchIn(hay)) return true
            } else if (hay.contains(clean)) {
                return true
            }
        }
        val parsed = splitLocal(channel.name)
        if (parsed != null && sameCity(parsed.first, marketName)) return true
        return false
    }

    private fun bestLocal(
        channels: List<GuideChannel>,
        lat: Double,
        lon: Double,
        networks: Set<String>?
    ): LocalResult {
        val ordered = markets.sortedBy { haversine(lat, lon, it.lat, it.lon) }
        val byNetwork = HashMap<String, MutableList<GuideChannel>>(8)
        for (channel in channels) {
            val net = localNetworkIn(channel.name, channel.group) ?: continue
            if (networks != null && net !in networks) continue
            byNetwork.getOrPut(net) { ArrayList() }.add(channel)
        }

        // Tier 1: Closest market(s) within 220 km
        var fallbackMarket: String? = null
        var fallbackByNet: LinkedHashMap<String, ChannelPick>? = null
        for (market in ordered) {
            val distance = haversine(lat, lon, market.lat, market.lon)
            if (fallbackByNet != null && distance > 220.0) break
            val picksByNet = pickLocalByNetwork(byNetwork, market.name, networks)
            if (picksByNet.isEmpty()) continue
            if (picksByNet.size >= 3) return LocalResult(market.name, picksByNet.values.toList())
            if (fallbackByNet == null || picksByNet.size > fallbackByNet.size) {
                fallbackMarket = market.name
                fallbackByNet = picksByNet
            }
        }

        // Tier 2: Supplement missing core networks from nearest same-state markets
        val primaryMarket = fallbackMarket ?: ordered.first().name
        val primaryState = primaryMarket.substringAfter(",", "").trim().uppercase()
        val chosenByNet = fallbackByNet ?: LinkedHashMap()
        if (chosenByNet.size < 4 && primaryState.length == 2) {
            for (market in ordered) {
                val st = market.name.substringAfter(",", "").trim().uppercase()
                if (st != primaryState) continue
                for ((net, _) in localNetworks) {
                    if (networks != null && net !in networks) continue
                    if (net in chosenByNet) continue
                    val hit = pickSingleLocal(byNetwork[net].orEmpty(), net, market.name)
                    if (hit != null) chosenByNet[net] = hit
                }
                if (chosenByNet.size >= 4) break
            }
        }

        // Tier 3: National US East/West affiliate fallback so a user NEVER gets 0 locals
        if (chosenByNet.size < 4) {
            val preferWest = lon < -105.0
            for ((net, group) in localNetworks) {
                if (networks != null && net !in networks) continue
                if (net in chosenByNet) continue
                val pool = byNetwork[net].orEmpty()
                if (pool.isEmpty()) continue
                val best = pool.sortedWith(
                    compareBy<GuideChannel> {
                        val l = it.name.lowercase()
                        when {
                            preferWest && l.contains("west") -> 0
                            !preferWest && l.contains("east") -> 0
                            l.contains("east") || l.contains("west") -> 1
                            else -> 2
                        }
                    }.thenBy { if (it.group.equals(group, ignoreCase = true) || it.group.contains("local", ignoreCase = true)) 0 else 1 }
                        .thenBy { it.name.length }
                ).firstOrNull() ?: continue
                chosenByNet[net] = ChannelPick(best.id, best.name)
            }
        }
        return LocalResult(primaryMarket, chosenByNet.values.toList())
    }

    private fun pickLocalByNetwork(
        byNetwork: Map<String, List<GuideChannel>>,
        marketName: String,
        networks: Set<String>? = null
    ): LinkedHashMap<String, ChannelPick> {
        val picks = LinkedHashMap<String, ChannelPick>()
        for ((network, _) in localNetworks) {
            if (networks != null && network !in networks) continue
            val chosen = pickSingleLocal(byNetwork[network].orEmpty(), network, marketName) ?: continue
            picks[network] = chosen
        }
        return picks
    }

    private fun pickSingleLocal(
        pool: List<GuideChannel>,
        network: String,
        marketName: String
    ): ChannelPick? {
        if (pool.isEmpty()) return null
        val group = localNetworks.firstOrNull { it.first == network }?.second.orEmpty()
        val hits = pool.filter { matchesMarketCity(it, marketName) }.sortedWith(
            compareBy<GuideChannel> { if (it.group.equals(group, ignoreCase = true)) 0 else 1 }
                .thenBy { if (it.group.contains("local", ignoreCase = true) || it.group.contains("affiliate", ignoreCase = true)) 0 else 1 }
                .thenBy { if (it.name == it.name.uppercase()) 1 else 0 }
                .thenBy { it.name.length }
        )
        val chosen = hits.firstOrNull() ?: return null
        return ChannelPick(chosen.id, chosen.name)
    }

    private fun pickNamed(channels: List<GuideChannel>, specs: List<Pair<String, String>>): List<ChannelPick> {
        val picks = ArrayList<ChannelPick>()
        for ((target, label) in specs) {
            val want = target.lowercase()
            var exact: GuideChannel? = null
            var fallback: GuideChannel? = null
            for (channel in channels) {
                val g = channel.group.uppercase()
                if (!g.startsWith("USA") && !g.startsWith("US ") && !g.startsWith("US|") && !g.startsWith("US:") && !g.contains("UNITED STATES")) continue
                val name = bare(channel.name)
                if (name == want) {
                    exact = channel
                    break
                }
                if (fallback == null && (name == "$want hd" || name == "$want fhd" || name == "$want east")) fallback = channel
            }
            val chosen = exact ?: fallback ?: continue
            picks.add(ChannelPick(chosen.id, chosen.name, label))
        }
        return picks
    }

    private fun networkToken(field: String): String {
        val known = setOf("ABC", "CBS", "NBC", "FOX", "CW", "PBS", "ION", "MY")
        val words = field.uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (word in words.take(3)) {
            if (word in known) return word
        }
        return words.firstOrNull().orEmpty()
    }

    private fun cityKey(value: String): String =
        value.substringBefore(",").trim().lowercase()

    private fun sameCity(left: String, right: String): Boolean {
        val a = cityKey(left)
        val b = cityKey(right)
        return a == b || a.startsWith("$b ") || b.startsWith("$a ")
    }

    private fun splitLocal(name: String): Pair<String, String>? {
        val parts = name.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val cityIndex = if (parts.size >= 3) 1 else 0
        val networkIndex = if (parts.size >= 3) 2 else 1
        if (parts.size <= networkIndex) return null
        val network = networkToken(parts[networkIndex])
        if (network.isEmpty()) return null
        return parts[cityIndex] to network
    }

    private fun bare(name: String): String = usPrefixRegex.replace(name.trim(), "").trim().lowercase()

    private fun teamScore(name: String, team: String): Int {
        val text = name.trim()
        if (text.isEmpty() || text.contains("radio:", ignoreCase = true)) return -1
        if (!hasWord(text, team)) return -1
        var score = 50
        if (Regex("\\([A-Z]{3,5}\\)").containsMatchIn(text)) score -= 40
        if (text.contains("teams:", ignoreCase = true)) score -= 35
        val stripped = text.replace(Regex("^(?:US:\\s*)?(?:NFL|NBA|MLB|NHL)\\s+", RegexOption.IGNORE_CASE), "").trim()
        val words = stripped.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val last = words.lastOrNull().orEmpty()
        if (last.equals(team, ignoreCase = true) && words.size <= 3) score += 40
        if (stripped.equals(team, ignoreCase = true)) score += 20
        return score - (text.length.coerceAtMost(80) / 10)
    }

    private fun bestTeamChannel(pool: List<GuideChannel>, team: String): GuideChannel? {
        var best: GuideChannel? = null
        var bestScore = -1
        for (channel in pool) {
            val score = teamScore(channel.name, team)
            if (score > bestScore) {
                best = channel
                bestScore = score
            }
        }
        return best
    }

    private fun slotLabel(name: String): String {
        val text = name.trim()
        val teams = sides(text)
        if (teams.size == 2) return teams.joinToString(" vs ")
        val head = text.substringBefore(":").replace(Regex("\\s+"), " ").trim()
        if (head.isNotEmpty() && !head.equals("no event", ignoreCase = true)) return head
        return text.replace(Regex(":?\\s*no event\\s*$", RegexOption.IGNORE_CASE), "").trim().ifEmpty { text }
    }

    private fun slotNumber(name: String): Int =
        digitsRegex.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 9999

    private fun hasWord(name: String, team: String): Boolean {
        val pattern = "(^|[^A-Za-z0-9])${Regex.escape(team)}([^A-Za-z0-9]|$)"
        return Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(name)
    }

    private fun sides(name: String): List<String> {
        val rest = name.substringAfter(":", name).substringBefore("@")
        val match = Regex("(.+?)\\s+(?:vs\\.?|at)\\s+(.+)", RegexOption.IGNORE_CASE).find(rest) ?: return emptyList()
        return listOf(cleanSide(match.groupValues[1]), cleanSide(match.groupValues[2])).filter { it.isNotEmpty() }
    }

    private fun cleanSide(value: String): String {
        val text = value.replace(Regex("^#\\d+\\s+"), "").replace(Regex("\\s+"), " ").trim()
        if (text.length < 2 || text.length > 42) return ""
        if (text.equals("no event", ignoreCase = true) || text.all { it.isDigit() }) return ""
        return text
    }

    private fun titleTeam(team: String): String = team.replace(Regex("\\b\\w")) { it.value.uppercase() }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val scale = PI / 180.0
        val halfLat = ((lat2 - lat1) * scale) / 2
        val halfLon = ((lon2 - lon1) * scale) / 2
        val a = sin(halfLat) * sin(halfLat) + cos(lat1 * scale) * cos(lat2 * scale) * sin(halfLon) * sin(halfLon)
        return 6371.0 * 2 * asin(sqrt(a.coerceAtMost(1.0)))
    }

    fun starterPack(channels: List<GuideChannel>, pack: String): List<ChannelPick> {
        val words = when (pack) {
            "news" -> listOf("news", "cnn", "msnbc", "fox news", "abc", "nbc", "cbs", "local")
            "sports" -> listOf("espn", "fox sport", "nfl", "nba", "mlb", "nhl", "bein", "sky sport")
            "movies" -> listOf("hbo", "starz", "showtime", "cinemax", "movie", "tcm")
            else -> listOf("nick", "disney", "cartoon", "pbs kids", "boomerang", "kids")
        }
        val picks = ArrayList<ChannelPick>(25)
        val seen = HashSet<String>()
        for (channel in channels) {
            val hay = (channel.name + " " + channel.group).lowercase()
            if (words.none { hay.contains(it) }) continue
            if (!seen.add(channel.id)) continue
            picks.add(ChannelPick(channel.id, channel.name))
            if (picks.size == 25) break
        }
        return picks
    }
}
