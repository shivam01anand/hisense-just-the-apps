"""Shows where each title on an IMDb watchlist streams, before anything is installed on the TV.

The home screen's watchlist row does the same lookup on the TV. Run this first to see what you'd get and
to find your IMDb user id for my-tv/config.json.

    python3 tools/watchlist-preview.py "https://www.imdb.com/user/p.xxxxxxxx/watchlist/"
    python3 tools/watchlist-preview.py ur12345678 --country IN

The watchlist must be public (IMDb > Watchlist > Edit > Privacy: Public). IMDb's and JustWatch's data
feeds used here are unofficial and for personal use.
"""
import argparse
import json
import re
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

# JustWatch technical name, label. Same order of preference as the home screen's default.
SERVICES = [("netflix", "Netflix"), ("amazonprimevideo", "Prime Video"), ("jiohotstar", "Hotstar"),
            ("appletvplus", "Apple TV"), ("sonyliv", "SonyLIV"), ("zee5", "Zee5")]
IMDB = "https://caching.graphql.imdb.com/"
IMDB_HEADERS = {"x-imdb-client-name": "imdb-web-next-localized"}


def post(url, body, headers=None):
    req = urllib.request.Request(url, json.dumps(body).encode(), {"content-type": "application/json", **(headers or {})})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def imdb_user(arg):
    """Accepts a profile or watchlist link, an ur id or a p. id; returns the ur id."""
    m = re.search(r"\bur\d+", arg)
    if m:
        return m.group(0)
    m = re.search(r"\bp\.[a-z0-9]+", arg)
    if not m:
        sys.exit("Could not find an IMDb user in %r. Paste the link from IMDb > Your watchlist > Share." % arg)
    q = 'query { userProfile(input: {profileId: "%s"}) { userId } }' % m.group(0)
    d = post(IMDB, {"query": q}, IMDB_HEADERS)
    user = ((d.get("data") or {}).get("userProfile") or {}).get("userId")
    if not user:
        sys.exit("IMDb did not recognise that profile link.")
    return user


def watchlist(user):
    q = """query { predefinedList(classType: WATCH_LIST, userId: "%s") { items(first: 250, sort: {by: CREATED_DATE, order: DESC}) {
      edges { node { listItem { ... on Title { id titleText { text } releaseYear { year } } } } } } } }""" % user
    d = post(IMDB, {"query": q}, IMDB_HEADERS)
    lst = (d.get("data") or {}).get("predefinedList")
    if not lst:
        sys.exit("Could not read that watchlist. Is it public? (IMDb > Watchlist > Edit > Privacy: Public)")
    out = []
    for e in lst["items"]["edges"]:
        t = e["node"].get("listItem") or {}
        if "id" in t:
            out.append({"imdb": t["id"], "title": t["titleText"]["text"], "year": (t.get("releaseYear") or {}).get("year")})
    return out


JW = """query S($country: Country!, $q: String!) { popularTitles(country: $country, first: 5, filter: {searchQuery: $q}) {
  edges { node { content(country: $country, language: "en") { externalIds { imdbId } }
  offers(country: $country, platform: WEB) { monetizationType package { technicalName } } } } } }"""


def where(item, country):
    for attempt in range(3):
        try:
            d = post("https://apis.justwatch.com/graphql", {"query": JW, "variables": {"country": country, "q": item["title"]}})
            break
        except Exception:  # noqa: BLE001 - throttled or offline: wait and retry
            time.sleep(2 * (attempt + 1))
    else:
        return None
    for e in d.get("data", {}).get("popularTitles", {}).get("edges", []):
        n = e["node"]
        if n["content"]["externalIds"].get("imdbId") != item["imdb"]:
            continue
        techs = {o["package"]["technicalName"] for o in n["offers"] or [] if o["monetizationType"] in ("FLATRATE", "FREE", "ADS")}
        for tech, label in SERVICES:
            if tech in techs:
                return label
        return None
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("imdb", help="IMDb watchlist or profile link, or an ur id")
    ap.add_argument("--country", default="IN", help="two-letter country for streaming offers (default IN)")
    args = ap.parse_args()

    user = imdb_user(args.imdb)
    items = watchlist(user)
    print("IMDb user %s: %d titles on the watchlist. Checking where they stream in %s..." % (user, len(items), args.country))
    with ThreadPoolExecutor(4) as pool:
        places = list(pool.map(lambda i: where(i, args.country), items))

    counts = {}
    for p in places:
        counts[p or "Stremio / none of your apps"] = counts.get(p or "Stremio / none of your apps", 0) + 1
    print()
    for label, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        print("  %-28s %d" % (label, n))
    print("\nNewest additions you can watch now:")
    shown = 0
    for item, p in zip(items, places):
        if p and shown < 15:
            print("  %-12s %s (%s)" % (p, item["title"], item["year"] or "?"))
            shown += 1
    print('\nFor my-tv/config.json:  "watchlist_imdb_user": "%s", "watchlist_country": "%s"' % (user, args.country))


if __name__ == "__main__":
    main()
