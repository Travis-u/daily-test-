package com.puremusic.app;

import java.util.*;

/** Editorial seed catalogue. Metadata sources are recorded in CATALOG.md.
 * Energy/coldness/descriptions are editorial listening judgements, not acoustic measurements.
 * No NetEase IDs are guessed. Availability and editions vary by territory/account.
 */
public final class SongCatalog {
    public static final class Song {
        public final String id, title, artist, album, description, style, language;
        public final boolean vocals;
        public final int energy, cold;
        Song(String id, String title, String artist, String album, boolean vocals,
             int energy, int cold, String style, String language, String description) {
            this.id=id; this.title=title; this.artist=artist; this.album=album;
            this.vocals=vocals; this.energy=energy; this.cold=cold;
            this.style=style; this.language=language; this.description=description;
        }
        public String query() { return title + " " + artist; }
    }
    public static List<Song> songs() {
        return Collections.unmodifiableList(Arrays.asList(
            new Song("eno-11", "1/1", "Brian Eno", "Ambient 1: Music for Airports", false, 0, 2, "氛围", "", "钢琴音型与长留白，适合把注意力留给手头的事"),
            new Song("eno-22", "2/2", "Brian Eno", "Ambient 1: Music for Airports", false, 0, 3, "氛围", "", "缓慢铺开的合成器，空间感比旋律更突出"),
            new Song("eno-ending", "An Ending (Ascent)", "Brian Eno", "Apollo: Atmospheres and Soundtracks", false, 0, 2, "氛围", "", "悬浮的长音，温和而没有强拍点"),
            new Song("eno-stars", "Under Stars", "Brian Eno", "Apollo: Atmospheres and Soundtracks", false, 1, 4, "氛围", "", "低频与幽暗音色，适合偏冷的夜间氛围"),
            new Song("eno-drift", "Drift", "Brian Eno", "Apollo: Atmospheres and Soundtracks", false, 0, 4, "氛围", "", "漂浮的音色，节奏存在感很低"),
            new Song("eno-signals", "Signals", "Brian Eno", "Apollo: Atmospheres and Soundtracks", false, 1, 4, "氛围", "", "稀疏电子纹理，偏冷、少旋律牵引"),
            new Song("frahm-less", "Less", "Nils Frahm", "Felt", false, 1, 2, "钢琴", "", "柔软钢琴与细小机械声，适合安静写题"),
            new Song("frahm-says", "Says", "Nils Frahm", "Spaces", false, 3, 3, "电子", "", "循环电子音型逐渐堆高；后段较强，不适合极低刺激"),
            new Song("frahm-hammers", "Hammers", "Nils Frahm", "Spaces", false, 4, 2, "钢琴", "", "快速钢琴音型，适合想要推进感时"),
            new Song("frahm-son", "Son", "Nils Frahm", "Solo Remains", false, 1, 3, "钢琴", "", "钢琴与留白，情绪较内敛"),
            new Song("frahm-him", "Him", "Nils Frahm", "Solo Remains", false, 1, 2, "钢琴", "", "独奏钢琴，编制简单、节奏克制"),
            new Song("frahm-immerse", "Immerse!", "Nils Frahm", "Solo", false, 2, 2, "钢琴", "", "独奏钢琴，音型流动但仍保留呼吸感"),
            new Song("sakamoto-andata", "andata", "坂本龙一", "async", false, 0, 4, "钢琴", "", "钢琴与幽微环境纹理，冷静、疏离"),
            new Song("sakamoto-bibo", "Bibo No Aozora (2024 Remaster)", "坂本龙一", "/04 /05 (2024 Remaster)", false, 1, 2, "钢琴", "", "旋律清楚的钢琴，适合想保留一点情绪时"),
            new Song("sakamoto-energy", "Energy Flow (2024 Remaster)", "坂本龙一", "/04 /05 (2024 Remaster)", false, 1, 1, "钢琴", "", "轻柔的钢琴旋律，比冷氛围更温暖"),
            new Song("tycho-awake", "Awake", "Tycho", "Awake", false, 3, 1, "电子", "", "鼓点与吉他持续推进，适合想清醒一点时"),
            new Song("tycho-see", "See", "Tycho", "Awake", false, 3, 2, "电子", "", "层叠电子节拍，流动感明显"),
            new Song("tycho-spectre", "Spectre", "Tycho", "Awake", false, 3, 2, "电子", "", "明亮电子纹理与稳定拍点"),
            new Song("tycho-apogee", "Apogee", "Tycho", "Awake (Deluxe Version)", false, 2, 2, "电子", "", "电子层次与较平稳的推进感"),
            new Song("xx-angels", "Angels", "The xx", "Coexist", true, 0, 3, "独立", "英语", "近距离的轻声演唱，伴奏很克制"),
            new Song("xx-unfold", "Unfold", "The xx", "Coexist", true, 1, 4, "独立", "英语", "人声与稀疏伴奏，适合冷一点的夜晚"),
            new Song("xx-our-song", "Our Song", "The xx", "Coexist", true, 1, 3, "独立", "英语", "低声对唱与留白，适合想听人声又怕喧闹时")
        ));
    }
}
