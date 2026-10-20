package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The watchlist row: titles from an IMDb watchlist, each saved with the one place to open it. That is the
 * title's page in a streaming app the owner has (from JustWatch's offers for the country), or Stremio.
 *
 * Nothing runs at boot and nothing is preloaded. When Home is shown and the saved list is older than
 * {@link #REFRESH_MS}, one background thread fetches the list, looks up the titles it has not seen (and a
 * few stale ones), saves title, poster and link, and hands the new list to the UI. A click only fires the
 * saved link. IMDb's and JustWatch's feeds are unofficial; if either fails, the last saved list stays.
 */
final class Watchlist {
    private static final String TAG = "TvHome.Watchlist";
    private static final long REFRESH_MS = 12 * 60 * 60 * 1000L;
    /** After a failed refresh (TV offline, a feed down), try again on the next Home after this long. */
    private static final long RETRY_MS = 30 * 60 * 1000L;
    /** Streaming catalogues change; a title's place is looked up again after this long. */
    private static final long RECHECK_MS = 3 * 24 * 60 * 60 * 1000L;
    /** Re-checks per refresh, so a refresh after the first one stays a handful of requests. */
    private static final int MAX_RECHECKS = 25;
    /** Bumped when the saved list gains fields; an older list is refreshed on the next Home.
     *  2: genres, rating. 3: runtime, countries, plot, directors, votes. 4: 300 px posters, collections. */
    private static final int DATA_VERSION = 4;
    /** Titles per collection (Hindi, this year, best of this year). */
    private static final int COLLECTION_SIZE = 30;
    /** Titles kept in Continue. */
    private static final int HISTORY_SIZE = 15;

    static final String STREMIO = "Stremio";

    static final class Item {
        String imdb, title, type, posterUrl, service, pkg, intentUri;
        int year;
        long checked;
        /** IMDb's rating out of 10 and its number of votes; 0 when it has none. */
        double rating;
        int votes;
        int runtimeMin;
        String plot;
        List<String> genres = new ArrayList<>();
        List<String> countries = new ArrayList<>();
        List<String> directors = new ArrayList<>();
    }

    /** A chip on the groups row and the posters it opens. */
    static final class Group {
        final String label;
        final List<Item> items;

        Group(String label, List<Item> items) {
            this.label = label;
            this.items = items;
        }
    }

    /** A list from beyond the watchlist: new great titles in a language, or this year's popular and best. */
    static final class Collection {
        final String label;
        /** Language collections go first on the row; the first chip is the one open when Home appears. */
        final boolean lead;
        final List<Item> items;

        Collection(String label, boolean lead, List<Item> items) {
            this.label = label;
            this.lead = lead;
            this.items = items;
        }
    }

    /** Everything the row is built from: the watchlist, the collections, and what was opened from Home. */
    static final class Data {
        final List<Item> watchlist;
        final List<Collection> collections;
        final List<Item> history;

        Data(List<Item> watchlist, List<Collection> collections, List<Item> history) {
            this.watchlist = watchlist;
            this.collections = collections;
            this.history = history;
        }
    }

    /**
     * The groups row, for someone who thinks in moods and genres, not apps: language collections first (the
     * first chip is open when Home appears), Continue (what you opened from Home, newest first), Recently added
     * to the watchlist, this year's popular and best, then the watchlist's genres, best rated first. A genre that
     * covers most of the watchlist (Drama, on many lists) narrows nothing, so it is left out, and so is any genre
     * with fewer than three titles.
     */
    static List<Group> groups(Data d) {
        List<Group> out = new ArrayList<>();
        for (Collection c : d.collections) if (c.lead && !c.items.isEmpty()) out.add(new Group(c.label, c.items));
        if (!d.history.isEmpty()) out.add(new Group("Continue", d.history));
        if (!d.watchlist.isEmpty()) out.add(new Group("Recently added", d.watchlist));
        for (Collection c : d.collections) if (!c.lead && !c.items.isEmpty()) out.add(new Group(c.label, c.items));
        Map<String, List<Item>> byGenre = new HashMap<>();
        for (Item it : d.watchlist) {
            for (String g : it.genres) {
                List<Item> l = byGenre.get(g);
                if (l == null) byGenre.put(g, l = new ArrayList<>());
                l.add(it);
            }
        }
        List<Map.Entry<String, List<Item>>> genres = new ArrayList<>(byGenre.entrySet());
        Collections.sort(genres, (a, b) -> b.getValue().size() != a.getValue().size()
                ? b.getValue().size() - a.getValue().size() : a.getKey().compareTo(b.getKey()));
        // Inside a genre, best rated first: picking a genre means "show me the good ones".
        for (Map.Entry<String, List<Item>> e : genres) {
            int n = e.getValue().size();
            if (n < 3 || n * 4 > d.watchlist.size() * 3) continue;
            List<Item> byRating = new ArrayList<>(e.getValue());
            Collections.sort(byRating, (a, b) -> Double.compare(b.rating, a.rating));
            out.add(new Group(e.getKey(), byRating));
        }
        return out;
    }

    /** A streaming service the owner has: JustWatch's technical name, the label shown, the app's package. */
    private static final class Service {
        final String tech, label, pkg;

        Service(String tech, String label, String pkg) {
            this.tech = tech;
            this.label = label;
            this.pkg = pkg;
        }
    }

    interface Listener {
        void onWatchlist(Data data);
    }

    private static final AtomicBoolean running = new AtomicBoolean(false);

    private Watchlist() {
    }

    /** The row shows when there is an IMDb user or any collection (config.xml, overlaid by config.json). */
    static boolean enabled(Context c) {
        Config cfg = Config.load(c);
        return !cfg.watchlistImdbUser.isEmpty() || !cfg.watchlistLanguages.isEmpty() || cfg.watchlistThisYear;
    }

    // ---- saved list ------------------------------------------------------------------------------

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "watchlist");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File poster(Context c, String imdb) {
        return new File(new File(dir(c), "posters300"), imdb + ".jpg");
    }

    /** The saved watchlist, collections and history; empty lists when there are none yet. */
    static Data load(Context c) {
        List<Collection> cols = new ArrayList<>();
        try {
            JSONArray a = readJson(new File(dir(c), "collections.json"));
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                cols.add(new Collection(o.getString("label"), o.optBoolean("lead"), items(o.optJSONArray("items"))));
            }
        } catch (Exception e) {
            Log.w(TAG, "saved collections unreadable", e);
            cols.clear();
        }
        return new Data(loadItems(c, "items.json"), cols, loadItems(c, "history.json"));
    }

    private static List<Item> loadItems(Context c, String name) {
        try {
            return items(readJson(new File(dir(c), name)));
        } catch (Exception e) {
            Log.w(TAG, name + " unreadable, starting over", e);
            return new ArrayList<>();
        }
    }

    private static JSONArray readJson(File f) throws IOException, JSONException {
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
        }
    }

    private static List<Item> items(JSONArray a) throws JSONException {
        List<Item> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        return out;
    }

    private static Item fromJson(JSONObject o) throws JSONException {
        Item it = new Item();
        it.imdb = o.getString("imdb");
        it.title = o.optString("title");
        it.type = o.optString("type");
        it.year = o.optInt("year");
        it.posterUrl = o.optString("poster", null);
        it.service = o.optString("service", STREMIO);
        it.pkg = o.optString("pkg", null);
        it.intentUri = o.optString("intent", null);
        it.checked = o.optLong("checked");
        it.rating = o.optDouble("rating", 0);
        it.votes = o.optInt("votes");
        it.runtimeMin = o.optInt("runtime");
        it.plot = o.optString("plot", null);
        strings(o.optJSONArray("genres"), it.genres);
        strings(o.optJSONArray("countries"), it.countries);
        strings(o.optJSONArray("directors"), it.directors);
        return it;
    }

    private static JSONObject toJson(Item it) throws JSONException {
        return new JSONObject()
                .put("imdb", it.imdb).put("title", it.title).put("type", it.type).put("year", it.year)
                .put("poster", it.posterUrl).put("service", it.service).put("pkg", it.pkg)
                .put("intent", it.intentUri).put("checked", it.checked)
                .put("rating", it.rating).put("votes", it.votes).put("runtime", it.runtimeMin).put("plot", it.plot)
                .put("genres", new JSONArray(it.genres)).put("countries", new JSONArray(it.countries))
                .put("directors", new JSONArray(it.directors));
    }

    private static JSONArray toJson(List<Item> items) throws JSONException {
        JSONArray a = new JSONArray();
        for (Item it : items) a.put(toJson(it));
        return a;
    }

    private static void strings(JSONArray a, List<String> into) {
        for (int k = 0; a != null && k < a.length(); k++) {
            String v = a.optString(k);
            if (!v.isEmpty()) into.add(v);
        }
    }

    private static void write(Context c, String name, JSONArray a) throws IOException {
        File tmp = new File(dir(c), name + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(dir(c), name))) throw new IOException("rename failed");
    }

    /**
     * Puts a title at the front of Continue. A small file on the TV and no network: the app it opens in keeps
     * the position, so finding it again is all Continue has to do.
     */
    static void remember(Context c, Item item) {
        try {
            List<Item> h = loadItems(c, "history.json");
            for (int i = h.size() - 1; i >= 0; i--) if (h.get(i).imdb.equals(item.imdb)) h.remove(i);
            h.add(0, item);
            while (h.size() > HISTORY_SIZE) h.remove(h.size() - 1);
            write(c, "history.json", toJson(h));
        } catch (Exception e) {
            Log.w(TAG, "could not save Continue", e);
        }
    }

    // ---- refresh ---------------------------------------------------------------------------------

    /** Refreshes on a background thread when the saved list is stale; the listener runs on the main thread. */
    static void refreshIfStale(Context context, Listener listener) {
        if (!enabled(context)) return;
        SharedPreferences prefs = context.getSharedPreferences("watchlist", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        boolean outdated = prefs.getInt("version", 1) < DATA_VERSION;
        if (now - prefs.getLong("tried", 0) < RETRY_MS) return;
        if (!outdated && now - prefs.getLong("ok", 0) < REFRESH_MS) return;
        if (!running.compareAndSet(false, true)) return;
        prefs.edit().putLong("tried", now).apply();
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                Data data = refresh(c, prefs);
                prefs.edit().putLong("ok", System.currentTimeMillis()).putInt("version", DATA_VERSION).apply();
                new Handler(Looper.getMainLooper()).post(() -> listener.onWatchlist(data));
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "refresh failed, keeping the saved list", e);
            } finally {
                running.set(false);
            }
        }, "watchlist").start();
    }

    private static Data refresh(Context c, SharedPreferences prefs) throws IOException, JSONException {
        Data before = load(c);
        Map<String, Item> saved = new HashMap<>();
        for (Collection col : before.collections) for (Item it : col.items) saved.put(it.imdb, it);
        for (Item it : before.watchlist) saved.put(it.imdb, it);
        Config cfg = Config.load(c);
        List<Service> services = installedServices(c, cfg.watchlistServices);
        // A service added (an app installed, or the list changed) can hold any title: look every one up again.
        StringBuilder sig = new StringBuilder();
        for (Service sv : services) sig.append(sv.tech).append(',');
        boolean servicesChanged = !sig.toString().equals(prefs.getString("services", ""));
        // The 200 px posters from before version 4 are too soft at the new size.
        deleteTree(new File(dir(c), "posters"));

        Placer placer = new Placer(c, saved, services, cfg.watchlistCountry, servicesChanged);
        List<Item> watchlist = cfg.watchlistImdbUser.isEmpty() ? new ArrayList<>() : fetchImdbWatchlist(cfg.watchlistImdbUser);
        for (Item it : watchlist) placer.place(it);

        List<Collection> cols = new ArrayList<>();
        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        String types = "titleTypeConstraint: {anyTitleTypeIds: [\"movie\", \"tvSeries\", \"tvMiniSeries\"]}";
        for (String lang : cfg.watchlistLanguages) {
            // New and well liked: from the start of last year, 7+ with enough votes to mean it, most popular first.
            // Only titles streaming on an installed app: this chip is for whoever just wants to press OK and
            // watch, so a film still in cinemas has no place in it. Twice as many are fetched to fill it.
            String label = new java.util.Locale(lang).getDisplayLanguage(java.util.Locale.ENGLISH);
            cols.add(collection(placer, label, true, COLLECTION_SIZE * 2, true, "releaseDateConstraint: {releaseDateRange: {start: \"" + (year - 1)
                    + "-01-01\", end: \"" + year + "-12-31\"}}, " + types
                    + ", userRatingsConstraint: {aggregateRatingRange: {min: 7}, ratingsCountRange: {min: 1000}}"
                    + ", languageConstraint: {anyPrimaryLanguages: [\"" + lang + "\"]}", "POPULARITY", "ASC"));
        }
        if (cfg.watchlistThisYear) {
            String thisYear = "releaseDateConstraint: {releaseDateRange: {start: \"" + year + "-01-01\", end: \"" + year
                    + "-12-31\"}}, " + types;
            // This year's most popular, minus the ones people didn't like; and the best of the year with real votes.
            cols.add(collection(placer, String.valueOf(year), false, COLLECTION_SIZE, false, thisYear
                    + ", userRatingsConstraint: {aggregateRatingRange: {min: 6.5}, ratingsCountRange: {min: 5000}}", "POPULARITY", "ASC"));
            cols.add(collection(placer, "Best of " + year, false, COLLECTION_SIZE, false, thisYear
                    + ", userRatingsConstraint: {ratingsCountRange: {min: 20000}}", "USER_RATING", "DESC"));
        }

        write(c, "items.json", toJson(watchlist));
        JSONArray a = new JSONArray();
        for (Collection col : cols) a.put(new JSONObject().put("label", col.label).put("lead", col.lead).put("items", toJson(col.items)));
        write(c, "collections.json", a);
        prefs.edit().putString("services", sig.toString()).apply();
        return new Data(watchlist, cols, loadItems(c, "history.json"));
    }

    /**
     * One collection; a failed search leaves it empty rather than failing the whole refresh. With streamingOnly,
     * titles that would fall back to Stremio are left out, and the first COLLECTION_SIZE that stream are kept.
     */
    private static Collection collection(Placer placer, String label, boolean lead, int fetch, boolean streamingOnly,
                                         String constraints, String sortBy, String order) {
        List<Item> items = new ArrayList<>();
        try {
            for (Item it : fetchSearch(constraints, sortBy, order, fetch)) {
                if (items.size() >= COLLECTION_SIZE) break;
                if (streamingOnly) placer.placeOnly(it);
                else placer.place(it);
                if (streamingOnly && STREMIO.equals(it.service)) continue;
                if (streamingOnly) fetchPoster(placer.c, it);
                items.add(it);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "collection " + label + " failed", e);
        }
        return new Collection(label, lead, items);
    }

    /** Where each title opens, shared by the watchlist and the collections so no title is looked up twice. */
    private static final class Placer {
        final Context c;
        final Map<String, Item> saved;
        final Map<String, Item> done = new HashMap<>();
        final List<Service> services;
        final String country;
        final boolean servicesChanged;
        final long now = System.currentTimeMillis();
        int rechecks;

        Placer(Context c, Map<String, Item> saved, List<Service> services, String country, boolean servicesChanged) {
            this.c = c;
            this.saved = saved;
            this.services = services;
            this.country = country;
            this.servicesChanged = servicesChanged;
        }

        void place(Item it) {
            placeOnly(it);
            fetchPoster(c, it);
        }

        /** Works out where the title opens without downloading its poster (it may yet be left out). */
        void placeOnly(Item it) {
            Item known = done.get(it.imdb);
            if (known != null) {
                copyPlace(known, it);
                return;
            }
            Item old = saved.get(it.imdb);
            boolean stale = old == null || servicesChanged || now - old.checked > RECHECK_MS;
            if (old != null && (!stale || (!servicesChanged && rechecks >= MAX_RECHECKS))) {
                copyPlace(old, it);
            } else {
                if (old != null) rechecks++;
                try {
                    findPlace(it, services, country);
                    it.checked = now;
                } catch (IOException | JSONException e) {
                    // JustWatch hiccup: keep what we knew, try this title again next time.
                    if (old != null) copyPlace(old, it);
                    else it.service = STREMIO;
                    it.checked = old != null ? old.checked : 0;
                }
            }
            done.put(it.imdb, it);
        }

        private static void copyPlace(Item from, Item to) {
            to.service = from.service;
            to.pkg = from.pkg;
            to.intentUri = from.intentUri;
            to.checked = from.checked;
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** The configured services, in order of preference, whose app is installed and enabled. */
    private static List<Service> installedServices(Context c, List<String> lines) {
        List<Service> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String line : lines) {
            String[] p = line.split("\\|");
            if (p.length != 3) continue;
            if (pm.getLeanbackLaunchIntentForPackage(p[2]) == null && pm.getLaunchIntentForPackage(p[2]) == null) continue;
            out.add(new Service(p[0], p[1], p[2]));
        }
        return out;
    }

    /** The fields the row, the info line and the details card use, for one IMDb title. */
    private static final String TITLE_FIELDS = "id titleText { text } releaseYear { year } titleType { id } primaryImage { url }"
            + " ratingsSummary { aggregateRating voteCount } titleGenres { genres { genre { text } } }"
            + " runtime { seconds } countriesOfOrigin { countries { text } } plot { plotText { plainText } }"
            + " directors: credits(first: 2, filter: {categories: [\"director\"]}) { edges { node { name { nameText { text } } } } }";

    private static List<Item> fetchImdbWatchlist(String user) throws IOException, JSONException {
        String q = "query { predefinedList(classType: WATCH_LIST, userId: \"" + user + "\") {"
                + " items(first: 250, sort: {by: CREATED_DATE, order: DESC}) { edges { node { listItem {"
                + " ... on Title { " + TITLE_FIELDS + " } } } } } } }";
        JSONObject d = imdb(q);
        JSONArray edges = d.getJSONObject("data").getJSONObject("predefinedList").getJSONObject("items").getJSONArray("edges");
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < edges.length(); i++) {
            Item it = parseTitle(edges.getJSONObject(i).getJSONObject("node").optJSONObject("listItem"));
            if (it != null) out.add(it);   // a list entry that is not a title (person, image) is skipped
        }
        return out;
    }

    /** IMDb's advanced search, as on imdb.com/search/title. */
    private static List<Item> fetchSearch(String constraints, String sortBy, String order, int first) throws IOException, JSONException {
        String q = "query { advancedTitleSearch(first: " + first + ", constraints: { " + constraints + " },"
                + " sort: {sortBy: " + sortBy + ", sortOrder: " + order + "}) { edges { node { title { " + TITLE_FIELDS
                + " } } } } }";
        JSONArray edges = imdb(q).getJSONObject("data").getJSONObject("advancedTitleSearch").getJSONArray("edges");
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < edges.length(); i++) {
            Item it = parseTitle(edges.getJSONObject(i).getJSONObject("node").optJSONObject("title"));
            if (it != null) out.add(it);
        }
        return out;
    }

    private static JSONObject imdb(String query) throws IOException, JSONException {
        return post("https://caching.graphql.imdb.com/", new JSONObject().put("query", query),
                "x-imdb-client-name", "imdb-web-next-localized");
    }

    private static Item parseTitle(JSONObject t) throws JSONException {
        if (t == null || !t.has("id")) return null;
        Item it = new Item();
        it.imdb = t.getString("id");
        it.title = t.getJSONObject("titleText").getString("text");
        JSONObject year = t.optJSONObject("releaseYear");
        it.year = year != null ? year.optInt("year") : 0;
        JSONObject type = t.optJSONObject("titleType");
        it.type = type != null ? type.optString("id") : "movie";
        JSONObject img = t.optJSONObject("primaryImage");
        // IMDb's image server scales on request: 300 px wide (about 30 KB) is sharp at the poster's size on a
        // 1080p screen, instead of a 2000 px original.
        it.posterUrl = img != null ? img.getString("url").replaceFirst("\\._V1_.*\\.jpg$", "._V1_UX300_.jpg") : null;
        JSONObject ratings = t.optJSONObject("ratingsSummary");
        it.rating = ratings != null ? ratings.optDouble("aggregateRating", 0) : 0;
        if (Double.isNaN(it.rating)) it.rating = 0;
        it.votes = ratings != null ? ratings.optInt("voteCount") : 0;
        JSONObject runtime = t.optJSONObject("runtime");
        it.runtimeMin = runtime != null ? runtime.optInt("seconds") / 60 : 0;
        JSONObject plot = t.optJSONObject("plot");
        JSONObject plotText = plot != null ? plot.optJSONObject("plotText") : null;
        it.plot = plotText != null && !plotText.isNull("plainText") ? plotText.optString("plainText") : null;
        JSONObject coo = t.optJSONObject("countriesOfOrigin");
        JSONArray cs = coo != null ? coo.optJSONArray("countries") : null;
        for (int k = 0; cs != null && k < cs.length(); k++) {
            String v = cs.getJSONObject(k).optString("text");
            if (!v.isEmpty()) it.countries.add(v);
        }
        JSONObject dirs = t.optJSONObject("directors");
        JSONArray ds = dirs != null ? dirs.optJSONArray("edges") : null;
        for (int k = 0; ds != null && k < ds.length(); k++) {
            JSONObject name = ds.getJSONObject(k).getJSONObject("node").optJSONObject("name");
            JSONObject nt = name != null ? name.optJSONObject("nameText") : null;
            if (nt != null && !nt.optString("text").isEmpty()) it.directors.add(nt.optString("text"));
        }
        JSONObject tg = t.optJSONObject("titleGenres");
        JSONArray gs = tg != null ? tg.optJSONArray("genres") : null;
        for (int k = 0; gs != null && k < gs.length(); k++) {
            JSONObject g = gs.getJSONObject(k).optJSONObject("genre");
            if (g != null && !g.optString("text").isEmpty()) it.genres.add(g.optString("text"));
        }
        return it;
    }

    private static final String JW_QUERY = "query S($country: Country!, $q: String!) {"
            + " popularTitles(country: $country, first: 5, filter: {searchQuery: $q}) { edges { node {"
            + " content(country: $country, language: \"en\") { externalIds { imdbId } }"
            + " offers(country: $country, platform: WEB) { monetizationType standardWebURL"
            + " deeplinkURL(platform: ANDROID_TV) package { technicalName } } } } } }";

    /** Sets the item's service and link: the first service in preference order that streams it, else Stremio. */
    private static void findPlace(Item it, List<Service> services, String country) throws IOException, JSONException {
        it.service = STREMIO;
        it.pkg = null;
        it.intentUri = null;
        if (services.isEmpty()) return;
        JSONObject vars = new JSONObject().put("country", country).put("q", it.title);
        JSONObject d = post("https://apis.justwatch.com/graphql", new JSONObject().put("query", JW_QUERY).put("variables", vars));
        JSONArray edges = d.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
        for (int i = 0; i < edges.length(); i++) {
            JSONObject n = edges.getJSONObject(i).getJSONObject("node");
            JSONObject ids = n.getJSONObject("content").optJSONObject("externalIds");
            if (ids == null || !it.imdb.equals(ids.optString("imdbId"))) continue;
            JSONArray offers = n.optJSONArray("offers");
            if (offers == null) return;
            for (Service s : services) {
                for (int k = 0; k < offers.length(); k++) {
                    JSONObject o = offers.getJSONObject(k);
                    String money = o.optString("monetizationType");
                    // Subscription or free only: rent and buy are not "what can I watch".
                    if (!money.equals("FLATRATE") && !money.equals("FREE") && !money.equals("ADS")) continue;
                    if (!s.tech.equals(o.getJSONObject("package").optString("technicalName"))) continue;
                    // optString turns an explicit JSON null into the text "null", so check isNull first.
                    Intent intent = offerIntent(s, o.isNull("standardWebURL") ? null : o.optString("standardWebURL"),
                            o.isNull("deeplinkURL") ? null : o.optString("deeplinkURL"));
                    if (intent == null) continue;
                    it.service = s.label;
                    it.pkg = s.pkg;
                    it.intentUri = intent.toUri(Intent.URI_INTENT_SCHEME);
                    return;
                }
            }
            return;
        }
    }

    /**
     * The link that opens the title. JustWatch's Android TV links for Netflix and Prime Video start playback, so
     * those use the web URL of the title page, which both apps open in place. SonyLIV ignores its own sony://
     * link and plays the title from its web URL. Hotstar's hotstar://content link opens its title page.
     * Whatever the link says, it may only open the service's app.
     */
    private static Intent offerIntent(Service s, String web, String deeplink) {
        if (web != null && (s.tech.equals("netflix") || s.tech.equals("amazonprimevideo") || s.tech.equals("sonyliv"))) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(web)).setPackage(s.pkg);
            if (s.tech.equals("netflix")) i.putExtra("source", "30");   // Netflix's launch source for TV home screens
            return i;
        }
        if (deeplink != null) {
            try {
                Intent i = Intent.parseUri(deeplink, Intent.URI_INTENT_SCHEME);
                if (i.getAction() == null) i.setAction(Intent.ACTION_VIEW);
                i.setComponent(null);
                i.setSelector(null);
                i.setFlags(0);
                i.setPackage(s.pkg);
                return i;
            } catch (URISyntaxException ignored) {
            }
        }
        return web != null ? new Intent(Intent.ACTION_VIEW, Uri.parse(web)).setPackage(s.pkg) : null;
    }

    private static void fetchPoster(Context c, Item it) {
        if (it.posterUrl == null) return;
        File f = poster(c, it.imdb);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(it.posterUrl).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                out.write(readAll(in));
            } finally {
                conn.disconnect();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();   // no poster this time; the tile shows the title instead
        }
    }

    // ---- opening ---------------------------------------------------------------------------------

    /** Opens the item where it streams, or in Stremio; falls back to the app itself if its link is refused. */
    static void open(Context c, Item it) {
        PackageManager pm = c.getPackageManager();
        Intent i = null;
        if (!STREMIO.equals(it.service) && it.intentUri != null) {
            try {
                i = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
            } catch (URISyntaxException ignored) {
            }
        }
        if (i == null) i = stremioIntent(c, it);
        if (i == null) {
            Toast.makeText(c, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // A title link must start the app's screen afresh: Stremio 1.10 ignores a link while it is already
        // open in the background and just comes to the front. Plain app launches keep where you left off.
        if (i.getData() != null) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Intent app = i.getPackage() != null ? launchIntent(pm, i.getPackage()) : null;
            if (app == null) {
                Toast.makeText(c, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
            c.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /**
     * Stremio's title link, stremio:///detail/{type}/{id}/{videoId}, works in its current apps. Stremio 1.2
     * (still common on Android TV 9 sets) reads every stremio:// link as an add-on to install and shows an
     * error, so there the app just opens and a toast names the title to search for.
     */
    private static Intent stremioIntent(Context c, Item it) {
        PackageManager pm = c.getPackageManager();
        for (String pkg : new String[]{"com.stremio.one", "com.stremio.app"}) {
            Intent app = launchIntent(pm, pkg);
            if (app == null) continue;
            if (legacyStremio(pm, pkg)) {
                Toast.makeText(c, c.getString(R.string.watchlist_find_in_stremio, it.title), Toast.LENGTH_LONG).show();
                return app;
            }
            boolean series = !(it.type.equals("movie") || it.type.equals("tvMovie") || it.type.equals("short") || it.type.equals("video"));
            String uri = series
                    ? "stremio:///detail/series/" + it.imdb
                    : "stremio:///detail/movie/" + it.imdb + "/" + it.imdb;
            return new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg);
        }
        return null;
    }

    private static boolean legacyStremio(PackageManager pm, String pkg) {
        try {
            PackageInfo info = pm.getPackageInfo(pkg, 0);
            String v = info.versionName != null ? info.versionName : "";
            return v.matches("^1\\.[0-5](\\..*)?$");
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    // ---- http ------------------------------------------------------------------------------------

    private static JSONObject post(String url, JSONObject body, String... headers) throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("content-type", "application/json");
        for (int i = 0; i + 1 < headers.length; i += 2) conn.setRequestProperty(headers[i], headers[i + 1]);
        try {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != 200) throw new IOException(url + " answered " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                JSONObject d = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
                if (d.optJSONObject("data") == null) throw new IOException(url + " returned no data");
                return d;
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16 * 1024];
        for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }
}
