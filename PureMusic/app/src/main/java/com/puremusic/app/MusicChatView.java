package com.puremusic.app;

import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;

/** Native chat screen: fixed composer + independent scroll + persistent local conversation. */
public final class MusicChatView extends LinearLayout {
    public interface Actions {
        void close(); void togglePlayback(); void open(SongCatalog.Song song);
        boolean playHere(SongCatalog.Song song);
    }
    private final Actions actions;
    private final RecommendationProvider provider=new RecommendationEngine();
    private final LinearLayout messages;
    private final ScrollView scroll;
    private final TextView preferences, nowPlaying;
    private final Button play;
    private final EditText input;
    private JSONArray events=new JSONArray();
    private final SharedPreferences storage;
    public MusicChatView(Context context, Actions actions) {
        super(context); this.actions=actions;
        storage=context.getSharedPreferences("music_chat_v1",Context.MODE_PRIVATE);
        setOrientation(VERTICAL); setPadding(dp(16),dp(8),dp(16),dp(8));
        setBackgroundColor(Color.rgb(247,247,243));
        LinearLayout header=row();
        Button back=button("返回"); back.setOnClickListener(v->actions.close()); header.addView(back);
        TextView title=label("找歌对话",20,true); header.addView(title,new LayoutParams(0,dp(48),1));
        Button clear=button("清空"); clear.setOnClickListener(v->confirmClear());
        header.addView(clear); addView(header);
        TextView mode=label("离线对话原型 · 尚未接入大模型",12,false); addView(mode);
        preferences=label("还没有限定偏好",12,false); preferences.setPadding(0,dp(6),0,dp(8)); addView(preferences);
        scroll=new ScrollView(context); scroll.setFillViewport(true);
        messages=new LinearLayout(context); messages.setOrientation(VERTICAL);
        scroll.addView(messages); addView(scroll,new LayoutParams(LayoutParams.MATCH_PARENT,0,1));
        LinearLayout mini=row(); nowPlaying=label("等待网易云播放",12,false);
        mini.addView(nowPlaying,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        play=button("▶"); play.setContentDescription("播放或暂停当前歌曲");
        play.setOnClickListener(v->actions.togglePlayback()); mini.addView(play,new LayoutParams(dp(52),dp(48))); addView(mini);
        LinearLayout composer=row();
        input=new EditText(context); input.setTextSize(14); input.setHint("想听什么？也可以继续调整上一组");
        input.setMaxLines(4); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND); input.setBackground(bg(Color.WHITE));
        composer.addView(input,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        Button send=button("发送"); send.setOnClickListener(v->send()); composer.addView(send,new LayoutParams(dp(64),dp(48))); addView(composer);
        input.setOnEditorActionListener((v,id,event)->{ if(id==EditorInfo.IME_ACTION_SEND){send();return true;} return false; });
        welcome();
        try {
            events=new JSONArray(storage.getString("events","[]"));
            for(int i=0;i<events.length();i++) {
                JSONObject event=events.getJSONObject(i);
                if(event.getString("type").equals("user")) {
                    String value=event.getString("value"); bubble(value,true); render(provider.respond(value));
                } else {
                    String id=event.getString("value"); provider.select(id);
                    for(SongCatalog.Song s:SongCatalog.songs()) if(s.id.equals(id)) bubble("参照：《"+s.title+"》 · "+s.artist,true);
                }
            }
        } catch(JSONException e) { events=new JSONArray(); provider.reset(); messages.removeAllViews(); welcome(); save(); }
        bottom();
    }
    private void confirmClear() {
        new android.app.AlertDialog.Builder(getContext()).setMessage("清空本机对话和找歌偏好？")
            .setNegativeButton("取消",null).setPositiveButton("清空",(dialog,which)->{
                events=new JSONArray(); provider.reset(); messages.removeAllViews(); welcome();
                preferences.setText("还没有限定偏好"); save();
            }).show();
    }
    private void welcome() {
        bubble("告诉我场景、声音或情绪，我会给出具体歌曲和理由。比如：写物理，安静一点，不要人声。\n可以继续说“第二个太吵”。想保留某位歌手，先选“以此为参照”，再说“这个歌手可以，再冷一点”。\n目前曲库有限，不认识的要求会向你说明；网易云可用性和版本以 App 内为准。",false);
    }
    private void send() {
        String value=input.getText().toString().trim();
        if(value.isEmpty()) return;
        if(value.length()>1000) { input.setError("一句控制在 1000 字以内"); return; }
        if(events.length()>=100) { input.setError("对话已达 100 条，请清空后重新开始"); return; }
        input.setText(""); bubble(value,true); record("user",value); render(provider.respond(value)); bottom();
    }
    private void record(String type,String value) {
        try { JSONObject e=new JSONObject(); e.put("type",type); e.put("value",value); events.put(e); save(); }
        catch(JSONException ignored) {}
    }
    private void save() { storage.edit().putString("events",events.toString()).apply(); }
    private void render(RecommendationEngine.Reply reply) {
        preferences.setText(reply.preferences); bubble(reply.text,false);
        int n=0;
        for(RecommendationEngine.Item item:reply.items) {
            final SongCatalog.Song s=item.song;
            LinearLayout card=new LinearLayout(getContext()); card.setOrientation(VERTICAL);
            card.setPadding(dp(14),dp(12),dp(14),dp(12)); card.setBackground(bg(Color.WHITE));
            card.addView(label((++n)+". "+s.title,16,true));
            card.addView(label(s.artist+" · "+s.album,12,false));
            TextView reason=label(item.reason,13,false); reason.setPadding(0,dp(8),0,dp(8)); card.addView(reason);
            LinearLayout buttons=row();
            Button reference=button("以此为参照"); reference.setOnClickListener(v->{
                if(events.length()>=100) { Toast.makeText(getContext(),"请清空后开始新对话",Toast.LENGTH_SHORT).show(); return; }
                provider.select(s.id); record("select",s.id); bubble("参照：《"+s.title+"》 · "+s.artist,true); bottom();
            });
            Button open=button("网易云打开"); open.setOnClickListener(v->{
                provider.select(s.id); if(events.length()<100) record("select",s.id); actions.open(s);
            });
            buttons.addView(reference,new LayoutParams(0,dp(48),1)); buttons.addView(open,new LayoutParams(0,dp(48),1)); card.addView(buttons);
            LinearLayout extra=row();
            Button copy=button("复制搜索词"); copy.setOnClickListener(v->{
                ClipboardManager cm=(ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if(cm!=null) {cm.setPrimaryClip(ClipData.newPlainText("纯音搜索词",s.query())); Toast.makeText(getContext(),"已复制："+s.query(),Toast.LENGTH_SHORT).show();}
            });
            Button here=button("尝试在此播放"); here.setOnClickListener(v->{
                provider.select(s.id); if(events.length()<100) record("select",s.id);
                if(!actions.playHere(s)) Toast.makeText(getContext(),"网易云未开放搜索播放。请用“网易云打开”搜索这首。",Toast.LENGTH_LONG).show();
            });
            extra.addView(copy,new LayoutParams(0,dp(48),1)); extra.addView(here,new LayoutParams(0,dp(48),1)); card.addView(extra);
            LayoutParams lp=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT); lp.setMargins(0,dp(6),0,dp(10)); messages.addView(card,lp);
        }
    }
    public void updatePlayer(String title,String artist,boolean playing) {
        nowPlaying.setText(title+" · "+artist); play.setText(playing?"Ⅱ":"▶");
    }
    private void bubble(String value,boolean user) {
        TextView v=label((user?"你\n":"纯音\n")+value,14,false); v.setTextColor(Color.rgb(35,35,32));
        v.setPadding(dp(14),dp(12),dp(14),dp(12)); v.setTextIsSelectable(true);
        v.setBackground(bg(user?Color.rgb(226,234,223):Color.rgb(237,237,231)));
        LayoutParams lp=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);
        lp.setMargins(user?dp(24):0,dp(6),user?0:dp(24),dp(8)); messages.addView(v,lp);
    }
    private void bottom() { scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN)); }
    private LinearLayout row() { LinearLayout v=new LinearLayout(getContext()); v.setOrientation(HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView label(String value,int size,boolean bold) {
        TextView v=new TextView(getContext()); v.setText(value); v.setTextSize(size);
        v.setTextColor(bold?Color.rgb(25,25,25):Color.rgb(105,105,98));
        if(bold) v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return v;
    }
    private Button button(String title) { Button v=new Button(getContext()); v.setText(title); v.setAllCaps(false); v.setTextSize(12); v.setMinWidth(0); return v; }
    private GradientDrawable bg(int color) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(14)); return d; }
    private int dp(int n) {return Math.round(n*getResources().getDisplayMetrics().density);}
}
