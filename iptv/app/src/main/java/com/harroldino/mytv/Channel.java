package com.harroldino.mytv;

/** One playable entry from a playlist. */
public final class Channel {
    /** Position in the playlist, starting at 1. Used as the channel number you type on the remote. */
    public final int number;
    public final String name;
    public final String url;
    public final String group;
    public final String logo;
    /** Matches the channel to the XMLTV guide. */
    public final String tvgId;

    public Channel(int number, String name, String url, String group, String logo, String tvgId) {
        this.number = number;
        this.name = name;
        this.url = url;
        this.group = group;
        this.logo = logo;
        this.tvgId = tvgId;
    }
}
