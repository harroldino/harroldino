package com.harroldino.mytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The first working screen: the "channel surfer" layout (concept 02).
 * Opens straight into the last channel. Up/Down change channel, Left flips back,
 * OK shows the channel list, number keys jump to a channel, Menu sets the playlist link.
 */
@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity {

    private static final int ACCENT = Color.rgb(255, 138, 44);
    private static final long BANNER_MS = 4000;
    private static final long DIGITS_MS = 1500;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Playlist playlist;
    private List<Channel> channels = new ArrayList<>();
    private int current = -1;
    private int previous = -1;
    private int retries;

    private ExoPlayer player;
    private PlayerView video;
    private LinearLayout banner;
    private TextView bannerNumber, bannerName, bannerGroup;
    private TextView digits, message;
    private ListView list;

    private final Runnable hideBanner = () -> banner.setVisibility(View.GONE);
    private final Runnable commitDigits = this::commitDigits;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        playlist = new Playlist(this);
        buildViews();

        channels = playlist.cached();
        if (!channels.isEmpty()) playStart();
        if (playlist.url() == null) askForPlaylist();
        else refresh(channels.isEmpty());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player == null) createPlayer();
        if (current >= 0) play(current, false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Stop streaming in the background: Fire Sticks are short on memory and bandwidth.
        if (player != null) { player.release(); player = null; video.setPlayer(null); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }

    // ---------------------------------------------------------------- playback

    private void createPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("MyTV/1.0 (Linux; Android) ExoPlayer")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http))
                .build();
        player.setPlayWhenReady(true);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) showMessage("Loading…");
                else if (state == Player.STATE_READY) { retries = 0; hideMessage(); }
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                // Live streams drop now and then. Try again a few times before giving up.
                if (retries++ < 3 && current >= 0) {
                    showMessage("Reconnecting…");
                    ui.postDelayed(() -> play(current, false), 2000L * retries);
                } else {
                    showMessage("This channel isn't working right now.\nTry another one.");
                }
            }
        });
        video.setPlayer(player);
    }

    private void playStart() {
        String last = playlist.lastChannelUrl();
        int idx = 0;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).url.equals(last)) { idx = i; break; }
        }
        current = idx;
        if (player != null) play(idx, true);
    }

    private void play(int index, boolean showInfo) {
        if (channels.isEmpty() || player == null) return;
        index = ((index % channels.size()) + channels.size()) % channels.size();
        if (index != current) { previous = current; retries = 0; }
        current = index;
        Channel c = channels.get(index);
        player.setMediaItem(MediaItem.fromUri(c.url));
        player.prepare();
        playlist.setLastChannelUrl(c.url);
        if (showInfo) showBanner(c);
    }

    // ---------------------------------------------------------------- remote

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (list.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { hideList(); return true; }
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            typeDigit(keyCode - KeyEvent.KEYCODE_0);
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                play(current - 1, true); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                play(current + 1, true); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                if (previous >= 0) play(previous, true); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_INFO:
                if (current >= 0) showBanner(channels.get(current)); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showList(); return true;
            case KeyEvent.KEYCODE_MENU:
                askForPlaylist(); return true;
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
        String s = digits.getText().toString();
        digits.setVisibility(View.GONE);
        try {
            int n = Integer.parseInt(s);
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).number == n) { play(i, true); return; }
            }
            showToastMessage("No channel " + n);
        } catch (NumberFormatException ignored) { }
    }

    // ---------------------------------------------------------------- playlist

    private void refresh(boolean showProgress) {
        if (showProgress) showMessage("Loading your channels…");
        io.execute(() -> {
            try {
                List<Channel> fresh = playlist.download();
                ui.post(() -> {
                    boolean wasEmpty = channels.isEmpty();
                    String playing = current >= 0 && current < channels.size() ? channels.get(current).url : null;
                    channels = fresh;
                    current = -1;
                    for (int i = 0; i < fresh.size(); i++) if (fresh.get(i).url.equals(playing)) current = i;
                    previous = -1;
                    fillList();
                    if (wasEmpty || current < 0) playStart();
                    else hideMessage();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (channels.isEmpty()) showMessage("Couldn't load your playlist.\n" + e.getMessage() + "\n\nPress Menu ☰ to check the link.");
                });
            }
        });
    }

    private void askForPlaylist() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("http://provider.example/get.php?username=…&password=…&type=m3u_plus");
        input.setSingleLine(true);
        if (playlist.url() != null) input.setText(playlist.url());
        new AlertDialog.Builder(this)
                .setTitle("Playlist link")
                .setMessage("Enter the M3U link from your IPTV provider. For an Xtream Codes login, use the provider's get.php link with type=m3u_plus.")
                .setView(input)
                .setPositiveButton("Load", (d, w) -> {
                    String link = input.getText().toString().trim();
                    if (link.isEmpty()) return;
                    if (!link.contains("://")) link = "http://" + link;
                    playlist.setUrl(link);
                    channels = new ArrayList<>();
                    current = previous = -1;
                    if (player != null) player.stop();
                    refresh(true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- views

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new PlayerView(this);
        video.setUseController(false);
        video.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        video.setKeepContentOnPlayerReset(true);
        root.addView(video, match());

        // Bottom info banner.
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
        bannerGroup = text(16, 0xB3FFFFFF, false);
        words.addView(bannerName);
        words.addView(bannerGroup);
        banner.addView(bannerNumber);
        banner.addView(words);
        banner.setVisibility(View.GONE);
        root.addView(banner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Channel number being typed, top right.
        digits = text(64, Color.WHITE, true);
        digits.setShadowLayer(dp(8), 0, 0, Color.BLACK);
        digits.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        dlp.setMargins(0, dp(32), dp(48), 0);
        root.addView(digits, dlp);

        // Loading and error messages, centred.
        message = text(20, Color.WHITE, false);
        message.setGravity(Gravity.CENTER);
        message.setShadowLayer(dp(6), 0, 0, Color.BLACK);
        message.setVisibility(View.GONE);
        root.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Channel list down the left side.
        list = new ListView(this);
        list.setBackgroundColor(0xF0101318);
        list.setDivider(new ColorDrawable(0x22FFFFFF));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(ACCENT));
        list.setPadding(0, dp(24), 0, dp(24));
        list.setClipToPadding(false);
        list.setVisibility(View.GONE);
        list.setOnItemClickListener((parent, view, position, id) -> { hideList(); play(position, true); });
        root.addView(list, new FrameLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        fillList();

        setContentView(root);
    }

    private void fillList() {
        List<String> rows = new ArrayList<>();
        for (Channel c : channels) rows.add(c.number + "   " + c.name);
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
    }

    private void showList() {
        if (channels.isEmpty()) { askForPlaylist(); return; }
        hideBanner.run();
        list.setVisibility(View.VISIBLE);
        list.requestFocus();
        int at = Math.max(current, 0);
        list.setSelection(at);
    }

    private void hideList() {
        list.setVisibility(View.GONE);
        video.requestFocus();
    }

    private void showBanner(Channel c) {
        bannerNumber.setText(String.valueOf(c.number));
        bannerName.setText(c.name);
        bannerGroup.setText(c.group);
        banner.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideBanner);
        ui.postDelayed(hideBanner, BANNER_MS);
    }

    private void showMessage(String s) {
        message.setText(s);
        message.setVisibility(View.VISIBLE);
    }

    private void showToastMessage(String s) {
        showMessage(s);
        ui.postDelayed(this::hideMessage, 2000);
    }

    private void hideMessage() { message.setVisibility(View.GONE); }

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
