package com.harroldino.mytv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player.Listener;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen playback.
 * Live TV: Up/Down change channel, Left flips back, OK shows the channel list, number keys jump.
 * Movies and episodes: the usual seek bar, and it picks up where you stopped last time.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    private static final int ACCENT = Color.rgb(255, 138, 44);
    private static final long BANNER_MS = 4000;
    private static final long DIGITS_MS = 1500;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private List<Player.Item> items = new ArrayList<>();
    private boolean live;
    private int current = -1;
    private int previous = -1;
    private int retries;

    private ExoPlayer player;
    private PlayerView video;
    private LinearLayout banner;
    private TextView bannerNumber, bannerName, bannerSub;
    private TextView digits, message;
    private ListView list;

    private final Runnable hideBanner = () -> banner.setVisibility(View.GONE);
    private final Runnable commitDigits = this::commitDigits;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        items = new ArrayList<>(Player.queue);
        live = Player.queueLive;
        current = Math.max(0, Math.min(Player.queueIndex, items.size() - 1));
        if (items.isEmpty()) { finish(); return; }
        buildViews();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (items.isEmpty()) return;
        createPlayer();
        play(current, true, true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveProgress();
        // Stop streaming in the background: Fire Sticks are short on memory and bandwidth.
        if (player != null) { player.release(); player = null; video.setPlayer(null); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }

    // ---------------------------------------------------------------- playback

    private void createPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Player.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http))
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(30_000)
                .build();
        player.setPlayWhenReady(true);
        player.addListener(new Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == androidx.media3.common.Player.STATE_BUFFERING) showMessage("Loading…");
                else if (state == androidx.media3.common.Player.STATE_READY) { retries = 0; hideMessage(); }
                else if (state == androidx.media3.common.Player.STATE_ENDED && !live) {
                    Player.save(PlayerActivity.this, items.get(current), 1, 1); // finished: drop from Continue watching
                    if (current + 1 < items.size()) play(current + 1, true, false); else finish();
                }
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                // Streams drop now and then. Try again a few times before giving up.
                if (retries++ < 3) {
                    showMessage("Reconnecting…");
                    final long at = live || player == null ? 0 : player.getCurrentPosition();
                    ui.postDelayed(() -> { play(current, false, false); if (at > 0 && player != null) player.seekTo(at); }, 2000L * retries);
                } else {
                    showMessage(live ? "This channel isn't working right now.\nTry another one."
                            : "This won't play right now.\nPress Back and try again later.");
                }
            }
        });
        video.setPlayer(player);
    }

    private void play(int index, boolean showInfo, boolean resume) {
        if (player == null || items.isEmpty()) return;
        index = ((index % items.size()) + items.size()) % items.size();
        if (index != current) { saveProgress(); previous = current; retries = 0; }
        current = index;
        Player.Item it = items.get(index);
        player.setMediaItem(MediaItem.fromUri(it.url));
        player.prepare();
        if (!live && resume) {
            long at = Player.resumeAt(this, it.key);
            if (at > 0) { player.seekTo(at); toast("Picking up where you left off"); }
        }
        if (showInfo) showBanner(it);
    }

    private void saveProgress() {
        if (live || player == null || current < 0) return;
        Player.save(this, items.get(current), player.getCurrentPosition(), player.getDuration());
    }

    // ---------------------------------------------------------------- remote

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (list.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { hideList(); return true; }
            return super.onKeyDown(keyCode, event);
        }
        if (!live) return super.onKeyDown(keyCode, event); // PlayerView handles seek and pause
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            typeDigit(keyCode - KeyEvent.KEYCODE_0);
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                play(current - 1, true, false); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                play(current + 1, true, false); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                if (previous >= 0) play(previous, true, false); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_INFO:
                showBanner(items.get(current)); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showList(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (banner.getVisibility() == View.VISIBLE) { hideBanner.run(); return true; }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void typeDigit(int d) {
        String s = (digits.getVisibility() == View.VISIBLE ? digits.getText().toString() : "") + d;
        if (s.length() > 4) s = String.valueOf(d);
        digits.setText(s);
        digits.setVisibility(View.VISIBLE);
        ui.removeCallbacks(commitDigits);
        ui.postDelayed(commitDigits, DIGITS_MS);
    }

    private void commitDigits() {
        digits.setVisibility(View.GONE);
        try {
            int n = Integer.parseInt(digits.getText().toString());
            if (n >= 1 && n <= items.size()) play(n - 1, true, false);
            else toast("No channel " + n);
        } catch (NumberFormatException ignored) { }
    }

    // ---------------------------------------------------------------- views

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new PlayerView(this);
        video.setUseController(!live);
        video.setControllerShowTimeoutMs(3500);
        video.setShowNextButton(!live && items.size() > 1);
        video.setShowPreviousButton(false);
        video.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        video.setKeepContentOnPlayerReset(true);
        root.addView(video, match());

        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(48), dp(28), dp(48), dp(36));
        banner.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x99000000, 0x00000000}));
        bannerNumber = text(40, Color.WHITE, true);
        bannerNumber.setPadding(0, 0, dp(24), 0);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        bannerName = text(26, Color.WHITE, true);
        bannerSub = text(16, 0xB3FFFFFF, false);
        words.addView(bannerName);
        words.addView(bannerSub);
        banner.addView(bannerNumber);
        banner.addView(words);
        banner.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, live ? Gravity.BOTTOM : Gravity.TOP);
        if (!live) banner.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE6000000, 0x99000000, 0x00000000}));
        root.addView(banner, blp);

        digits = text(64, Color.WHITE, true);
        digits.setShadowLayer(dp(8), 0, 0, Color.BLACK);
        digits.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        dlp.setMargins(0, dp(32), dp(48), 0);
        root.addView(digits, dlp);

        message = text(20, Color.WHITE, false);
        message.setGravity(Gravity.CENTER);
        message.setShadowLayer(dp(6), 0, 0, Color.BLACK);
        message.setVisibility(View.GONE);
        root.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        list = new ListView(this);
        list.setBackgroundColor(0xF0101318);
        list.setDivider(new ColorDrawable(0x22FFFFFF));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(ACCENT));
        list.setPadding(0, dp(24), 0, dp(24));
        list.setClipToPadding(false);
        list.setVisibility(View.GONE);
        list.setOnItemClickListener((parent, view, position, id) -> { hideList(); play(position, true, false); });
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) rows.add((i + 1) + "   " + items.get(i).name);
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextColor(Color.WHITE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                t.setSingleLine(true);
                t.setPadding(dp(28), dp(12), dp(28), dp(12));
                return t;
            }
        });
        root.addView(list, new FrameLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        setContentView(root);
    }

    private void showList() {
        hideBanner.run();
        list.setVisibility(View.VISIBLE);
        list.requestFocus();
        list.setSelection(Math.max(current, 0));
    }

    private void hideList() {
        list.setVisibility(View.GONE);
        video.requestFocus();
    }

    private void showBanner(Player.Item it) {
        bannerNumber.setText(live ? String.valueOf(current + 1) : "");
        bannerNumber.setVisibility(live ? View.VISIBLE : View.GONE);
        bannerName.setText(it.name);
        bannerSub.setText(it.sub);
        banner.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideBanner);
        ui.postDelayed(hideBanner, BANNER_MS);
    }

    private void showMessage(String s) { message.setText(s); message.setVisibility(View.VISIBLE); }

    private void hideMessage() { message.setVisibility(View.GONE); }

    private void toast(String s) {
        showMessage(s);
        ui.postDelayed(this::hideMessage, 2500);
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
