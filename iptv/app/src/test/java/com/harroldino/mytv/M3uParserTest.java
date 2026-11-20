package com.harroldino.mytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

public class M3uParserTest {

    @Test
    public void readsM3uPlusAttributes() {
        M3uParser p = new M3uParser();
        List<Channel> list = p.parse("﻿#EXTM3U url-tvg=\"http://epg.example/guide.xml\"\n"
                + "#EXTINF:-1 tvg-id=\"news.au\" tvg-name=\"News 24\" tvg-logo=\"http://img/n.png\" group-title=\"News\",News 24 HD\n"
                + "http://host/live/u/p/101.ts\n"
                + "\n"
                + "#EXTINF:-1 tvg-id=\"\" group-title=\"Sport, Live\",Sports 1\n"
                + "#EXTVLCOPT:http-user-agent=Foo\n"
                + "http://host/live/u/p/102.m3u8\n");
        assertEquals("http://epg.example/guide.xml", p.epgUrl);
        assertEquals(2, list.size());

        Channel a = list.get(0);
        assertEquals(1, a.number);
        assertEquals("News 24 HD", a.name);
        assertEquals("News", a.group);
        assertEquals("http://img/n.png", a.logo);
        assertEquals("news.au", a.tvgId);
        assertEquals("http://host/live/u/p/101.ts", a.url);

        Channel b = list.get(1);
        assertEquals("Sports 1", b.name);           // comma inside the quoted group isn't the name split
        assertEquals("Sport, Live", b.group);
        assertNull(b.tvgId);
        assertEquals("http://host/live/u/p/102.m3u8", b.url);
    }

    @Test
    public void fallsBackToExtgrpAndTvgName() {
        List<Channel> list = new M3uParser().parse("#EXTM3U\n"
                + "#EXTINF:-1 tvg-name=\"Kids Zone\",\n"
                + "#EXTGRP:Kids\n"
                + "http://host/kids\n"
                + "#EXTINF:-1,Plain\n"
                + "http://host/plain\n");
        assertEquals("Kids Zone", list.get(0).name);
        assertEquals("Kids", list.get(0).group);
        assertEquals("Other", list.get(1).group);
    }

    @Test
    public void ignoresUrlsWithoutExtinf() {
        assertEquals(0, new M3uParser().parse("#EXTM3U\nhttp://stray\n").size());
    }
}
