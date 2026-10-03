package com.puremusic.app;
import java.util.*;
/** Executable behaviour tests without Android or third-party dependencies. */
public class RecommendationEngineTest {
    static void check(boolean value,String why) { if(!value) throw new AssertionError(why); }
    public static void main(String[] args) {
        RecommendationEngine e=new RecommendationEngine();
        RecommendationEngine.Reply first=e.respond("写物理，安静一点，不要人声");
        check(first.items.size()==3,"three concrete songs");
        for(RecommendationEngine.Item i:first.items) { check(!i.song.vocals && i.song.energy<=1,"hard constraints"); check(!i.song.album.isEmpty() && !i.reason.isEmpty(),"identity and reason"); }
        String rejected=first.items.get(1).song.id;
        RecommendationEngine.Reply second=e.respond("第二个太吵");
        check(!second.items.isEmpty(),"numbered refinement");
        for(RecommendationEngine.Item i:second.items) check(!i.song.id.equals(rejected) && i.song.energy<=Math.max(0,first.items.get(1).song.energy-1) && !i.song.vocals,"reject and reduce energy with context");
        e.reset(); first=e.respond("推荐");
        e.select(first.items.get(0).song.id);
        second=e.respond("这个歌手可以，再冷一点");
        check(!second.items.isEmpty(),"artist refinement has candidates");
        for(RecommendationEngine.Item i:second.items) check(i.song.artist.equals(first.items.get(0).song.artist) && i.song.cold>first.items.get(0).song.cold,"retain artist and colder");
        e.reset(); check(e.respond("这个歌手可以").items.isEmpty(),"ambiguous reference asks");
        check(e.respond("第三个太吵").items.isEmpty(),"invalid index asks");
        check(e.respond("我想听火星上猫写的爵士").items.isEmpty(),"unsupported asks, no invented songs");
        e.respond("不要人声"); second=e.respond("要人声");
        check(!second.items.isEmpty(),"latest explicit preference overrides");
        for(RecommendationEngine.Item i:second.items) check(i.song.vocals,"vocal override");
        e.reset(); e.respond("钢琴"); second=e.respond("不要钢琴");
        check(second.items.isEmpty(),"negative piano not misread as positive");
        e.reset(); Set<String> all=new HashSet<>();
        for(int n=0;n<15;n++) { second=e.respond(n==0?"推荐":"换一批"); for(RecommendationEngine.Item i:second.items) check(all.add(i.song.id),"no duplicate discovery"); }
        check(second.items.isEmpty(),"exhaustion is explicit");
        e.respond("重新开始"); check(e.respond("推荐").items.size()==3,"reset restores catalogue");
        RecommendationEngine replay=new RecommendationEngine(), original=new RecommendationEngine();
        String[] prompts={"不要人声","第二个太吵","再冷一点","换一批"};
        for(String prompt:prompts) {
            RecommendationEngine.Reply a=original.respond(prompt), b=replay.respond(prompt);
            check(a.text.equals(b.text) && a.preferences.equals(b.preferences),"deterministic persistence replay");
            check(a.items.size()==b.items.size(),"replayed count");
            for(int n=0;n<a.items.size();n++) check(a.items.get(n).song.id.equals(b.items.get(n).song.id),"replayed identity");
        }
        System.out.println("Recommendation behaviour checks passed");
    }
}
