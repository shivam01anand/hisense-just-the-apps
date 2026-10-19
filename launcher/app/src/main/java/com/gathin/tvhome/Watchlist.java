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

    static final String STREMIO = "Stremio";

    static final class Item {
        String imdb, title, type, posterUrl, service, pkg, intentUri;
        int year;
        long checked;
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
        void onWatchlist(List<Item> items);
    }

    private static final AtomicBoolean running = new AtomicBoolean(false);

    private Watchlist() {
    }

    /** The IMDb user, country and services come from config.xml, overlaid by config.json (see Config). */
    static boolean enabled(Context c) {
        return !Config.load(c).watchlistImdbUser.isEmpty();
    }

    // ---- saved list ------------------------------------------------------------------------------

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "watchlist");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File poster(Context c, String imdb) {
        return new File(new File(dir(c), "posters"), imdb + ".jpg");
    }

    static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        File f = new File(dir(c), "items.json");
        if (!f.exists()) return out;
        try (InputStream in = new FileInputStream(f)) {
            JSONArray a = new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
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
                out.add(it);
            }
        } catch (Exception e) {
            Log.w(TAG, "saved watchlist unreadable, starting over", e);
            out.clear();
        }
        return out;
    }

    private static void save(Context c, List<Item> items) throws IOException, JSONException {
        JSONArray a = new JSONArray();
        for (Item it : items) {
            a.put(new JSONObject()
                    .put("imdb", it.imdb).put("title", it.title).put("type", it.type).put("year", it.year)
                    .put("poster", it.posterUrl).put("service", it.service).put("pkg", it.pkg)
                    .put("intent", it.intentUri).put("checked", it.checked));
        }
        File tmp = new File(dir(c), "items.json.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(dir(c), "items.json"))) throw new IOException("rename failed");
    }

    // ---- refresh ---------------------------------------------------------------------------------

    /** Refreshes on a background thread when the saved list is stale; the listener runs on the main thread. */
    static void refreshIfStale(Context context, Listener listener) {
        if (!enabled(context)) return;
        SharedPreferences prefs = context.getSharedPreferences("watchlist", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("ok", 0) < REFRESH_MS || now - prefs.getLong("tried", 0) < RETRY_MS) return;
        if (!running.compareAndSet(false, true)) return;
        prefs.edit().putLong("tried", now).apply();
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                List<Item> items = refresh(c);
                prefs.edit().putLong("ok", System.currentTimeMillis()).apply();
                new Handler(Looper.getMainLooper()).post(() -> listener.onWatchlist(items));
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "refresh failed, keeping the saved list", e);
            } finally {
                running.set(false);
            }
        }, "watchlist").start();
    }

    private static List<Item> refresh(Context c) throws IOException, JSONException {
        Map<String, Item> saved = new HashMap<>();
        for (Item it : load(c)) saved.put(it.imdb, it);
        Config cfg = Config.load(c);
        List<Service> services = installedServices(c, cfg.watchlistServices);
        String country = cfg.watchlistCountry;
        long now = System.currentTimeMillis();
        int rechecks = 0;

        List<Item> items = fetchImdbWatchlist(cfg.watchlistImdbUser);
        for (Item it : items) {
            Item old = saved.get(it.imdb);
            boolean stale = old == null || now - old.checked > RECHECK_MS;
            if (old != null && (!stale || rechecks >= MAX_RECHECKS)) {
                it.service = old.service;
                it.pkg = old.pkg;
                it.intentUri = old.intentUri;
                it.checked = old.checked;
            } else {
                if (old != null) rechecks++;
                try {
                    findPlace(it, services, country);
                    it.checked = now;
                } catch (IOException | JSONException e) {
                    // JustWatch hiccup: keep what we knew, try this title again next time.
                    if (old != null) {
                        it.service = old.service;
                        it.pkg = old.pkg;
                        it.intentUri = old.intentUri;
                    } else {
                        it.service = STREMIO;
                    }
                    it.checked = old != null ? old.checked : 0;
                }
            }
            fetchPoster(c, it);
        }
        save(c, items);
        return items;
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

    private static List<Item> fetchImdbWatchlist(String user) throws IOException, JSONException {
        String q = "query { predefinedList(classType: WATCH_LIST, userId: \"" + user + "\") {"
                + " items(first: 250, sort: {by: CREATED_DATE, order: DESC}) { edges { node { listItem {"
                + " ... on Title { id titleText { text } releaseYear { year } titleType { id } primaryImage { url } }"
                + " } } } } } }";
        JSONObject d = post("https://caching.graphql.imdb.com/", new JSONObject().put("query", q),
                "x-imdb-client-name", "imdb-web-next-localized");
        JSONArray edges = d.getJSONObject("data").getJSONObject("predefinedList").getJSONObject("items").getJSONArray("edges");
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject t = edges.getJSONObject(i).getJSONObject("node").optJSONObject("listItem");
            if (t == null || !t.has("id")) continue;   // a list entry that is not a title (person, image)
            Item it = new Item();
            it.imdb = t.getString("id");
            it.title = t.getJSONObject("titleText").getString("text");
            JSONObject year = t.optJSONObject("releaseYear");
            it.year = year != null ? year.optInt("year") : 0;
            JSONObject type = t.optJSONObject("titleType");
            it.type = type != null ? type.optString("id") : "movie";
            JSONObject img = t.optJSONObject("primaryImage");
            // IMDb's image server scales on request: 200 px wide is about 16 KB instead of a 2000 px original.
            it.posterUrl = img != null ? img.getString("url").replaceFirst("\\._V1_.*\\.jpg$", "._V1_UX200_.jpg") : null;
            out.add(it);
        }
        return out;
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
     * The link that opens the title's page. JustWatch's Android TV links for Netflix and Prime Video start
     * playback, so those use the web URL of the title page, which both apps open in place. Hotstar's own
     * hotstar://content link opens its title page. Whatever the link says, it may only open the service's app.
     */
    private static Intent offerIntent(Service s, String web, String deeplink) {
        if (web != null && (s.tech.equals("netflix") || s.tech.equals("amazonprimevideo"))) {
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
