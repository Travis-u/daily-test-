package com.puremusic.app;

import java.util.*;
import java.util.regex.*;

/** Stateful offline prototype, not an LLM. Provider implementations must return catalogue
 * identities, and explain/clarify unsupported requests rather than invent titles or IDs. */
public final class RecommendationEngine implements RecommendationProvider {
    public static final class Item {
        public final SongCatalog.Song song;
        public final String reason;
        Item(SongCatalog.Song song, String reason) { this.song= song; this.reason=reason; }
    }
    public static final class Reply {
        public final String text, preferences;
        public final List<Item> items;
        Reply(String text, String preferences, List<Item> items) {
            this.text=text; this.preferences=preferences;
            this.items=Collections.unmodifiableList(items);
        }
    }
    private final List<SongCatalog.Song> catalog=SongCatalog.songs();
    private final Set<String> rejected=new HashSet<>(), shown=new HashSet<>();
    private List<Item> last=new ArrayList<>();
    private String selected="", artist="", style="", language="";
    private Boolean vocals=null;
    private int maxEnergy=4, targetEnergy=1, minCold=0;
    private boolean started=false;
    @Override public void reset() {
        rejected.clear(); shown.clear(); last=new ArrayList<>(); selected="";
        artist=""; style=""; language=""; vocals=null;
        maxEnergy=4; targetEnergy=1; minCold=0; started=false;
    }
    @Override public void select(String id) { if (find(id)!=null) selected=id; }
    private SongCatalog.Song find(String id) {
        for(SongCatalog.Song s:catalog) if(s.id.equals(id)) return s;
        return null;
    }
    private static boolean has(String p, String... words) {
        for(String w:words) if(p.contains(w)) return true;
        return false;
    }
    private Reply message(String text) { return new Reply(text, summary(), new ArrayList<>()); }
    private String summary() {
        List<String> parts=new ArrayList<>();
        if(vocals!=null) parts.add(vocals?"有人声":"无人声");
        if(maxEnergy<=1) parts.add("低刺激");
        else if(targetEnergy>=3) parts.add("有推进感");
        if(minCold>0) parts.add("偏冷");
        if(!artist.isEmpty()) parts.add(artist);
        if(!style.isEmpty()) parts.add(style);
        if(!language.isEmpty()) parts.add(language);
        return parts.isEmpty()?"还没有限定偏好":String.join(" · ",parts);
    }
    @Override public Reply respond(String message) {
        String p=message==null?"":message.trim().toLowerCase(Locale.ROOT);
        if(p.isEmpty()) return message("说说现在想听什么，或告诉我上一组哪里不合适。");
        if(has(p,"重新开始","清空偏好","换个话题","reset")) {
            reset(); return message("已清空偏好。现在想听怎样的音乐？");
        }
        SongCatalog.Song anchor=null;
        Matcher number=Pattern.compile("第\\s*([一二三123])(?:个|首|条)?").matcher(p);
        if(number.find()) {
            int n="一二三".indexOf(number.group(1));
            if(n<0) n=Integer.parseInt(number.group(1))-1;
            if(n>=last.size()) return message("上一组没有这个编号。请说歌名，或从上一组选择一首。");
            anchor=last.get(n).song;
        }
        for(SongCatalog.Song s:catalog) {
            if(p.contains(s.title.toLowerCase(Locale.ROOT))) { anchor=s; break; }
        }
        if(anchor==null && has(p,"这个","这首","他","她","这个歌手")) anchor=find(selected);
        if(anchor==null && has(p,"这个歌手","这位歌手","这首","这个"))
            return message("你指哪一首？可以说“第一个”，或点卡片的“以此为参照”。");
        // Parse into a working state so unsupported requests never silently mutate preferences.
        Boolean nextVocals=vocals;
        int nextMax=maxEnergy, nextTarget=targetEnergy, nextCold=minCold;
        String nextArtist=artist, nextStyle=style, nextLanguage=language;
        boolean understood=anchor!=null;
        if(has(p,"不要人声","无人声","纯音乐","不要歌词","instrumental","no vocals")) { nextVocals=false; understood=true; }
        else if(has(p,"要人声","有人声","想听人声","带歌词","vocals")) { nextVocals=true; understood=true; }
        else if(has(p,"人声不限","有无人声都行")) { nextVocals=null; understood=true; }
        if(has(p,"太吵","别太吵","不要太吵","安静","低刺激","克制","轻一点","quiet")) {
            nextMax=anchor==null?1:Math.max(0,anchor.energy-1); nextTarget=0; understood=true;
        }
        if(has(p,"冷一点","再冷","更冷","疏离","冷感","冷清","cold")) {
            nextCold=Math.min(4,Math.max(minCold+1,anchor==null?3:anchor.cold+1)); understood=true;
        }
        if(has(p,"温暖","暖一点","别冷","不要冷")) { nextCold=0; understood=true; }
        if(has(p,"清醒","提神","运动","骑车","有劲","节奏强","energy")) {
            nextMax=4; nextTarget=3; understood=true;
        }
        if(has(p,"学习","写题","作业","专注","物理","数学","study","focus")) {
            nextMax=1; nextTarget=0; understood=true;
        }
        if(has(p,"夜晚","晚上","睡前","失眠","night")) { nextMax=1; nextTarget=0; understood=true; }
        if(has(p,"不限定歌手","换个歌手","其他歌手","歌手不限")) { nextArtist=""; understood=true; }
        else if(anchor!=null && has(p,"歌手可以","歌手不错","同一个歌手","这位歌手","这个歌手")) {
            nextArtist=anchor.artist; understood=true;
        }
        for(SongCatalog.Song s:catalog) {
            if(p.contains(s.artist.toLowerCase(Locale.ROOT)) && !has(p,"不要这个歌手","换个歌手")) {
                nextArtist=s.artist; understood=true;
            }
        }
        if(has(p,"坂本","sakamoto")) { nextArtist="坂本龙一"; understood=true; }
        if(has(p,"不要钢琴","别钢琴")) return message("目前可改为氛围或电子。你想选哪一种？");
        if(has(p,"钢琴","piano")) { nextStyle="钢琴"; understood=true; }
        if(has(p,"电子","electronic")) { nextStyle="电子"; understood=true; }
        if(has(p,"氛围","ambient")) { nextStyle="氛围"; understood=true; }
        if(has(p,"独立","indie")) { nextStyle="独立"; understood=true; }
        if(has(p,"风格不限","类型不限")) { nextStyle=""; understood=true; }
        if(has(p,"英语","英文")) { nextLanguage="英语"; understood=true; }
        if(has(p,"中文","华语","日语","俄语","摇滚","爵士","古典","陈绮贞","朴树"))
            return message("当前离线曲库还没覆盖这个要求，我不会编造歌名。可以先试钢琴、氛围、电子或 The xx；完整找歌需要后续接入音乐目录与模型。");
        if(has(p,"语言不限")) { nextLanguage=""; understood=true; }
        boolean more=has(p,"换一批","再来","继续","推荐","找歌","随便","都可以","more");
        if(!understood && !(more && (started || has(p,"推荐","找歌","随便","都可以"))))
            return message("这一句我还不能可靠理解。你可以补充人声、安静程度、钢琴/电子，或说“第二个太吵”。当前是离线对话原型，还没有接入大模型。");
        vocals=nextVocals; maxEnergy=nextMax; targetEnergy=nextTarget; minCold=nextCold;
        artist=nextArtist; style=nextStyle; language=nextLanguage;
        if(anchor!=null) selected=anchor.id;
        if(anchor!=null && has(p,"太吵","不喜欢","不要这首","不行")) rejected.add(anchor.id);
        List<SongCatalog.Song> candidates=new ArrayList<>();
        for(SongCatalog.Song s:catalog) {
            if(rejected.contains(s.id) || shown.contains(s.id)) continue;
            if(vocals!=null && s.vocals!=vocals) continue;
            if(s.energy>maxEnergy || s.cold<minCold) continue;
            if(!artist.isEmpty() && !artist.equals(s.artist)) continue;
            if(!style.isEmpty() && !style.equals(s.style)) continue;
            if(!language.isEmpty() && (!s.vocals || !language.equals(s.language))) continue;
            candidates.add(s);
        }
        candidates.sort(Comparator.comparingInt(s -> Math.abs(s.energy-targetEnergy)*3));
        List<Item> result=new ArrayList<>();
        Set<String> artists=new HashSet<>();
        // Prefer diversity, then fill remaining slots without relaxing hard constraints.
        for(SongCatalog.Song s:candidates) {
            if(artists.add(s.artist)) result.add(item(s));
            if(result.size()==3) break;
        }
        for(SongCatalog.Song s:candidates) {
            if(result.size()==3) break;
            boolean exists=false; for(Item i:result) if(i.song.id.equals(s.id)) exists=true;
            if(!exists) result.add(item(s));
        }
        started=true;
        if(result.isEmpty()) return message("这组条件下，离线曲库没有未推荐过的曲目了。我保留你的限制；可以说“换个歌手”“风格不限”，或“重新开始”。");
        last=result;
        for(Item i:result) shown.add(i.song.id);
        String lead=anchor==null?"按你现在的偏好，先试这几首。":
            "以《"+anchor.title+"》为参照，继续按你的反馈调整。";
        return new Reply(lead+" 每组编号从 1 开始。",summary(),result);
    }
    private Item item(SongCatalog.Song s) {
        String fit=(s.vocals?"有人声":"无人声") + (maxEnergy<=1?"，编制/节奏较克制":"")
            +(minCold>0?"，音色偏冷":"");
        return new Item(s,fit+"。"+s.description+"。");
    }
}
