package com.harroldino.mytv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads M3U / M3U Plus playlists, the format every IPTV provider offers.
 * Xtream Codes logins work too: their playlist link is
 * {@code http://host:port/get.php?username=U&password=P&type=m3u_plus&output=ts}.
 */
public final class M3uParser {

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");

    /** The playlist's guide link, from {@code #EXTM3U url-tvg="..."} or {@code x-tvg-url}. Null if none. */
    public String epgUrl;

    public List<Channel> parse(String text) {
        try {
            return parse(new StringReader(text));
        } catch (IOException e) {
            throw new IllegalStateException(e); // a StringReader can't fail
        }
    }

    public List<Channel> parse(Reader in) throws IOException {
        BufferedReader r = new BufferedReader(in);
        List<Channel> out = new ArrayList<>();
        Map<String, String> attrs = null;
        String name = null;
        String extGroup = null;
        String line;
        boolean first = true;
        while ((line = r.readLine()) != null) {
            if (first) {
                if (line.startsWith("﻿")) line = line.substring(1); // byte order mark
                first = false;
            }
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXTM3U")) {
                Map<String, String> head = attributes(line);
                epgUrl = head.containsKey("url-tvg") ? head.get("url-tvg") : head.get("x-tvg-url");
            } else if (line.startsWith("#EXTINF")) {
                attrs = attributes(line);
                name = titleOf(line);
                extGroup = null;
            } else if (line.startsWith("#EXTGRP:")) {
                extGroup = line.substring(8).trim();
            } else if (line.startsWith("#")) {
                // #EXTVLCOPT, #KODIPROP and other player hints: not needed yet.
            } else if (attrs != null) {
                String group = attrs.get("group-title");
                if (group == null || group.isEmpty()) group = extGroup;
                if (group == null || group.isEmpty()) group = "Other";
                String title = name;
                if (title == null || title.isEmpty()) title = attrs.get("tvg-name");
                if (title == null || title.isEmpty()) title = "Channel " + (out.size() + 1);
                out.add(new Channel(out.size() + 1, title, line, group,
                        emptyToNull(attrs.get("tvg-logo")), emptyToNull(attrs.get("tvg-id"))));
                attrs = null;
                name = null;
            }
        }
        return out;
    }

    private static Map<String, String> attributes(String line) {
        Map<String, String> m = new HashMap<>();
        Matcher x = ATTR.matcher(line);
        while (x.find()) m.put(x.group(1).toLowerCase(), x.group(2));
        return m;
    }

    /** The display name is whatever follows the first comma that isn't inside quotes. */
    static String titleOf(String extinf) {
        boolean quoted = false;
        for (int i = 0; i < extinf.length(); i++) {
            char c = extinf.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) return extinf.substring(i + 1).trim();
        }
        return null;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
