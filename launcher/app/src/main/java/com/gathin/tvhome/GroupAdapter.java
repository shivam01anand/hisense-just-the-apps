package com.gathin.tvhome;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** The watchlist's groups row: "Recently added", "Top rated", genres. The selected one's posters show below. */
final class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.Holder> {

    interface Listener {
        void onGroupFocused(int position);

        /** A D-pad key on a chip. Return true when handled (focus stays put). */
        boolean onGroupKey(int position, int keyCode);
    }

    private final Listener listener;
    private final List<Watchlist.Group> groups = new ArrayList<>();
    private int selected = -1;

    GroupAdapter(Listener listener) {
        this.listener = listener;
    }

    void setGroups(List<Watchlist.Group> list) {
        groups.clear();
        groups.addAll(list);
        notifyDataSetChanged();
    }

    /** Marks the group whose posters are showing, so it stays lit while focus is on the posters. */
    void setSelected(int position) {
        int old = selected;
        selected = position;
        if (old >= 0 && old < groups.size()) notifyItemChanged(old);
        if (position >= 0 && position < groups.size()) notifyItemChanged(position);
    }

    @Override
    public int getItemCount() {
        return groups.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        h.chip.setText(groups.get(position).label);   // no counts: nobody picks a genre by how many it has
        h.chip.setSelected(position == selected);
    }

    final class Holder extends RecyclerView.ViewHolder {
        final TextView chip;

        Holder(View v) {
            super(v);
            chip = (TextView) v;
            v.setOnFocusChangeListener((view, focused) -> {
                int pos = getBindingAdapterPosition();
                if (focused && pos != RecyclerView.NO_POSITION) listener.onGroupFocused(pos);
            });
            v.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return pos != RecyclerView.NO_POSITION && listener.onGroupKey(pos, keyCode);
            });
        }
    }
}
