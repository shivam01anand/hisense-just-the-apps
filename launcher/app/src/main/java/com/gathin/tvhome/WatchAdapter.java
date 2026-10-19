package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posters for the watchlist row, each with a small chip naming where it opens. Posters are decoded off the
 * main thread from the files {@link Watchlist} saved, at RGB_565, into a small memory cache, so scrolling the
 * row never waits on the disk and a long list does not hold every bitmap.
 */
final class WatchAdapter extends RecyclerView.Adapter<WatchAdapter.Holder> {

    interface Listener {
        void onPosterFocused(int position, Watchlist.Item item);

        void onPosterClicked(Watchlist.Item item);

        /** A D-pad key on a poster. Return true when handled (focus stays put). */
        boolean onPosterKey(int position, int keyCode);
    }

    static final float FOCUS_SCALE = 1.10f;

    private final Context context;
    private final Listener listener;
    private final boolean reducedMotion;
    private final List<Watchlist.Item> items = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    /** About 70 posters at 200×296 RGB_565; the row shows ten at a time. */
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    WatchAdapter(Context context, Listener listener, boolean reducedMotion) {
        this.context = context;
        this.listener = listener;
        this.reducedMotion = reducedMotion;
    }

    void setItems(List<Watchlist.Item> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    Watchlist.Item itemAt(int position) {
        return items.get(position);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** The service's own colour, for the chip and the ambient glow while its poster is focused. */
    static int colorFor(String service) {
        switch (service) {
            case "Netflix":
                return 0xFFE50914;
            case "Prime Video":
                return 0xFF1A98FF;
            case "Hotstar":
                return 0xFF1F4FD8;
            case "Zee5":
                return 0xFF8230C6;
            default:
                return 0xFF7B5BD6;   // Stremio
        }
    }

    private static String chipFor(String service) {
        return "Prime Video".equals(service) ? "PRIME" : service.toUpperCase(java.util.Locale.ROOT);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_poster, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Watchlist.Item it = items.get(position);
        h.title.setText(it.title);
        h.chip.setText(chipFor(it.service));
        h.chip.setBackgroundTintList(ColorStateList.valueOf(colorFor(it.service)));
        h.image.setTag(it.imdb);
        Bitmap cached = cache.get(it.imdb);
        h.image.setImageBitmap(cached);
        h.title.setVisibility(cached == null ? View.VISIBLE : View.GONE);
        if (cached != null) return;
        final String imdb = it.imdb;
        decoder.execute(() -> {
            File f = Watchlist.poster(context, imdb);
            if (!f.exists()) return;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b;
            try {
                b = BitmapFactory.decodeFile(f.getPath(), opts);
            } catch (Exception | OutOfMemoryError e) {
                return;   // a poster must never take the home screen down; the title stays instead
            }
            if (b == null) return;
            cache.put(imdb, b);
            main.post(() -> {
                if (!imdb.equals(h.image.getTag())) return;   // the holder was recycled for another title
                h.image.setImageBitmap(b);
                h.title.setVisibility(View.GONE);
            });
        });
    }

    final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title;
        final TextView chip;

        Holder(View v) {
            super(v);
            image = v.findViewById(R.id.posterImage);
            title = v.findViewById(R.id.posterTitle);
            chip = v.findViewById(R.id.posterChip);
            v.setClipToOutline(true);   // rounded corners from poster_bg

            v.setOnClickListener(view -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) listener.onPosterClicked(items.get(pos));
            });
            v.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return pos != RecyclerView.NO_POSITION && listener.onPosterKey(pos, keyCode);
            });
            v.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1f;
                view.animate().cancel();
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(reducedMotion ? (focused ? 120 : 80) : (focused ? 240 : 160))
                        .setInterpolator(reducedMotion || !focused ? new DecelerateInterpolator() : new OvershootInterpolator(1.4f))
                        .withLayer()
                        .start();
                int pos = getBindingAdapterPosition();
                if (focused && pos != RecyclerView.NO_POSITION) listener.onPosterFocused(pos, items.get(pos));
            });
        }
    }
}
