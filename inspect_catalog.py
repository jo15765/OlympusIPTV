import json
import re
import urllib.error
import urllib.request

src = open(
    r"app/src/main/java/com/clearcast/tv/ui/login/LoginActivity.kt",
    encoding="utf-8",
).read()


def grab(name):
    return re.search(name + r' = "([^"]+)"', src).group(1)


host = grab("DEFAULT_URL").rstrip("/")
user = grab("DEFAULT_USER")
pw = grab("DEFAULT_PASS")
actions = [
    "get_vod_categories",
    "get_series_categories",
]
wanted = [
    "num",
    "stream_id",
    "series_id",
    "category_id",
    "category_name",
    "name",
    "container_extension",
]
for action in actions:
    url = host + "/player_api.php?username=" + user + "&password=" + pw + "&action=" + action
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "ClearCast"})
        with urllib.request.urlopen(request, timeout=45) as response:
            raw = response.read(500000)
            text = raw.decode("utf-8", "replace")
            code = response.status
    except urllib.error.HTTPError as error:
        body = error.read(180).decode("utf-8", "replace").replace(pw, "").replace(user, "")
        print(action, "HTTP", error.code, body[:120])
        continue
    except Exception as error:
        print(action, "ERR", type(error).__name__)
        continue
    try:
        data = json.loads(text)
    except Exception:
        snippet = text[:60].replace(pw, "")
        print(action, "NOTJSON", code, snippet)
        continue
    if isinstance(data, list):
        keys = list(data[0].keys()) if data and isinstance(data[0], dict) else []
        sample = {}
        if data and isinstance(data[0], dict):
            for key in wanted:
                if key in data[0]:
                    sample[key] = type(data[0][key]).__name__
        names = [item.get("category_name") for item in data if isinstance(item, dict)]
        print(action, "COUNT", len(data))
        print("NAMES", " | ".join(names))
    elif isinstance(data, dict):
        print(action, "OBJECT", code, list(data.keys())[:15])
    else:
        print(action, type(data).__name__, code)

for action in ["get_vod_streams", "get_series"]:
    url = host + "/player_api.php?username=" + user + "&password=" + pw + "&action=" + action
    request = urllib.request.Request(url, headers={"User-Agent": "ClearCast"})
    with urllib.request.urlopen(request, timeout=90) as response:
        length = response.headers.get("Content-Length")
        raw = response.read(1200).decode("utf-8", "replace")
    keys = re.findall(r'"([a-zA-Z0-9_]+)":', raw[:800])
    print(action, "LEN", length, "KEYS", keys)
