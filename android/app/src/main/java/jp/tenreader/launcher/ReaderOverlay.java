package jp.tenreader.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

final class ReaderOverlay implements AutoCloseable {
    static final java.util.concurrent.Executor DEFAULT_TEXT_EXECUTOR=java.util.concurrent.Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"ten-word-analysis");t.setDaemon(true);return t;});
    static java.util.concurrent.Executor textExecutor=DEFAULT_TEXT_EXECUTOR;
    private final Context context;
    private final WindowManager manager;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable onClose, onRetry;
    private final LinearLayout root,content;
    private final WindowManager.LayoutParams params;
    private FocusWordView word;
    private TextView status,speedLabel;
    private Button play;
    private Button previous,next;
    private Button autoStop;
    private SeekBar position;
    private EditText editor;
    private Bitmap preview;
    private List<String> units=new ArrayList<>();
    private int index,speed=600;
    private String mode="phrase",source="";
    private boolean playing,closed,finished,automatic,serif;
    private int fontSize=36,page=1;
    private Runnable onFinished,onCorrection;
    private String notice="";
    private volatile long textGeneration;
    private boolean preparing,requestedPlayback;
    private int pendingOffset;
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if (closed || !playing) return;
            if (index+1>=units.size()) { finished=true;pause();status.setText("読み終わりました。閉じると元の画面に戻れます。");if(automatic&&onFinished!=null)onFinished.run();return; }
            index++;render();schedule();
        }
    };

    ReaderOverlay(Context service,Runnable onClose,Runnable onRetry) {
        this.context=OverlayWindows.context(service);this.onClose=onClose;this.onRetry=onRetry;
        manager=context.getSystemService(WindowManager.class);
        speed=context.getSharedPreferences("native-reader",Context.MODE_PRIVATE).getInt("speed",600);
        speed=Math.max(200,Math.min(6000,speed));
        mode=context.getSharedPreferences("native-reader",0).getString("mode","phrase");if(!mode.equals("phrase")&&!mode.equals("word")&&!mode.equals("character"))mode="phrase";
        fontSize=Math.max(20,Math.min(64,context.getSharedPreferences("native-reader",0).getInt("font-size",36)));
        serif=context.getSharedPreferences("native-reader",0).getBoolean("serif",false);
        root=new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec,int heightSpec) {
                int max=Math.max(dp(100),OverlayWindows.safeBounds(context).height()-dp(28));
                int supplied=View.MeasureSpec.getSize(heightSpec);
                if (View.MeasureSpec.getMode(heightSpec)!=View.MeasureSpec.UNSPECIFIED) max=Math.min(max,supplied);
                super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(max,View.MeasureSpec.AT_MOST));
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(14),dp(10),dp(14),dp(12));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(255,252,246));bg.setCornerRadius(dp(24));bg.setStroke(dp(1),Color.rgb(221,220,207));root.setBackground(bg);
        LinearLayout header=row(root);
        TextView title=text(header,"ten. 画面の本文",16);title.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        Button close=button(header,"閉じる",onClose);close.setId(R.id.reader_close);
        ScrollView scroll=new ScrollView(context);root.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);scroll.addView(content);
        params=new WindowManager.LayoutParams(1,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_SECURE,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.START;
        params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        layout();showLoading();manager.addView(root,params);OwnOverlayWindows.add(root);
    }
    void layout() {
        if (closed) return;
        Rect safe=OverlayWindows.safeBounds(context);
        params.width=Math.max(dp(160),Math.min(dp(480),safe.width()-dp(24)));
        params.x=safe.left+(safe.width()-params.width)/2;
        params.y=safe.top+dp(12);
        if (root.isAttachedToWindow()) manager.updateViewLayout(root,params);
        root.requestLayout();
    }
    View view(){return root;}
    void setHidden(boolean hidden){if(!closed)root.setVisibility(hidden?View.GONE:View.VISIBLE);}
    void automatic(boolean enabled,int page,Runnable onFinished,Runnable onCorrection){this.automatic=enabled;this.page=page;this.onFinished=onFinished;this.onCorrection=onCorrection;if(autoStop!=null)autoStop.setVisibility(enabled?View.VISIBLE:View.GONE);if(word!=null)render();}
    void notice(String message){notice=message;if(word!=null)render();else if(status!=null&&!message.isEmpty())status.setText(message);}
    void showLoading() { pause();textGeneration++;preparing=false;content.removeAllViews();word=null;play=null;editor=null;status=text(content,"画面の文字を読み取っています…",16);status.setId(R.id.reader_status);text(content,"この画面の「閉じる」で中止できます。",12); }
    void showError(String message) {
        if (closed) return;pause();textGeneration++;preparing=false;content.removeAllViews();word=null;play=null;editor=null;status=text(content,message,15);status.setId(R.id.reader_status);button(content,"画面を読み取り直す",onRetry);
    }
    void showDocument(OcrDocument document,Bitmap preview) {
        String selected=document.body().text();showDocument(document,preview,selected,ReadingText.visibleCount(selected)>=12);
    }
    void showDocument(OcrDocument document,Bitmap preview,String selected,boolean autoplay) {
        if (closed) { if(preview!=null)preview.recycle();return; }
        pause();if(this.preview!=null&&this.preview!=preview&&!this.preview.isRecycled())this.preview.recycle();
        this.preview=preview;
        content.removeAllViews();
        status=text(content,"本文候補を選びました。",12);status.setId(R.id.reader_status);status.setLines(2);
        word=new FocusWordView(context);word.setSize(fontSize);word.setSerif(serif);word.setId(R.id.reader_word);content.addView(word,new LinearLayout.LayoutParams(-1,dp(116)));
        autoStop=null;if(automatic)autoStop=button(content,"自動スクロールを止める",()->{if(onCorrection!=null)onCorrection.run();});
        position=new SeekBar(context);position.setContentDescription("読む位置");content.addView(position);
        position.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){ if(fromUser){pause();finished=false;index=progress;render();} }
            @Override public void onStartTrackingTouch(SeekBar bar) { pause(); }
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout controls=row(content);
        previous=weightedButton(controls,"戻る",()->{pause();finished=false;index=Math.max(0,index-5);render();});
        play=weightedButton(controls,"再生",this::toggle);play.setId(R.id.reader_play);
        next=weightedButton(controls,"進む",()->{pause();finished=false;index=Math.min(Math.max(0,units.size()-1),index+5);render();});next.setId(R.id.reader_next);
        speedLabel=text(content,"",12);
        SeekBar speedBar=new SeekBar(context);speedBar.setMax(116);speedBar.setProgress((speed-200)/50);speedBar.setContentDescription("読む速度");content.addView(speedBar);
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser){ if(fromUser){speed=200+value*50;context.getSharedPreferences("native-reader",0).edit().putInt("speed",speed).apply();render();if(playing)schedule();} }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        LinearLayout options=row(content);
        Spinner modes=spinner(options,new String[]{"意味のまとまり","単語","一文字"});modes.setId(R.id.reader_mode);
        modes.setSelection(mode.equals("word")?1:mode.equals("character")?2:0);
        modes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int selected,long id){
                String next=new String[]{"phrase","word","character"}[selected];if(next.equals(mode))return;
                int offset=preparing?pendingOffset:0;if(!preparing)for(int i=0;i<index;i++)offset+=ReadingText.visibleCount(units.get(i));
                mode=next;context.getSharedPreferences("native-reader",0).edit().putString("mode",mode).apply();apply(source,false,offset);
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        Spinner font=spinner(options,new String[]{"標準","明朝"});font.setSelection(serif?1:0);font.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> p,View view,int value,long id){serif=value==1;context.getSharedPreferences("native-reader",0).edit().putBoolean("serif",serif).apply();word.setSerif(serif);}
            @Override public void onNothingSelected(AdapterView<?> p){}
        });
        LinearLayout correction=new LinearLayout(context);correction.setOrientation(LinearLayout.VERTICAL);correction.setVisibility(View.GONE);
        button(content,"範囲・文章・文字サイズを直す",()->{pause();if(automatic&&onCorrection!=null)onCorrection.run();correction.setVisibility(correction.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);if(correction.getVisibility()==View.GONE)setEditing(false);});
        content.addView(correction);
        text(correction,"読みたい行を枠で囲んで適用します。行の途中は文章欄で修正できます。",12);
        OcrDocument.Region body=document.bodyRegion();
        CropPreview crop=new CropPreview(context,preview,document,body);crop.setId(R.id.reader_crop);correction.addView(crop,new LinearLayout.LayoutParams(-1,dp(200)));
        editor=new EditText(context);editor.setId(R.id.reader_editor);editor.setMinLines(2);editor.setMaxLines(4);editor.setTextSize(14);editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200000)});editor.setContentDescription("読み取った本文を修正");correction.addView(editor,new LinearLayout.LayoutParams(-1,-2));
        button(correction,"文章を編集する",()->{setEditing(true);editor.requestFocus();editor.postDelayed(()->{if(!closed)context.getSystemService(InputMethodManager.class).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);},100);});
        LinearLayout ranges=row(correction);
        weightedButton(ranges,"範囲を適用",()->{String cropped=document.text(crop.region());editor.setText(cropped);apply(cropped,false);});
        weightedButton(ranges,"全文に戻す",()->{String all=document.text(new OcrDocument.Region(0,0,document.width,document.height));editor.setText(all);apply(all,false);});
        weightedButton(ranges,"本文だけに戻す",()->{String bodyText=document.body().text();editor.setText(bodyText);apply(bodyText,false);});
        Button apply=button(correction,"修正を反映して再生",()->{apply(editor.getText().toString(),true);setEditing(false);correction.setVisibility(View.GONE);});apply.setId(R.id.reader_apply);
        text(correction,"文字サイズ（20〜64）",12);
        SeekBar size=new SeekBar(context);size.setMax(22);size.setProgress((fontSize-20)/2);size.setContentDescription("文字サイズ");correction.addView(size);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser){fontSize=20+value*2;context.getSharedPreferences("native-reader",0).edit().putInt("font-size",fontSize).apply();word.setSize(fontSize);}
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        text(correction,"固有名詞の区切り辞書（任意・1行に1語）",12);
        EditText terms=new EditText(context);terms.setMinLines(2);terms.setMaxLines(4);terms.setTextSize(14);terms.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5000)});terms.setText(context.getSharedPreferences("native-reader",0).getString("protected-terms",""));terms.setContentDescription("ひとまとまりにする固有名詞");correction.addView(terms,new LinearLayout.LayoutParams(-1,-2));
        button(correction,"固有名詞を編集",()->{setEditing(true);terms.requestFocus();context.getSystemService(InputMethodManager.class).showSoftInput(terms,InputMethodManager.SHOW_IMPLICIT);});
        button(correction,"区切り辞書を保存",()->{context.getSharedPreferences("native-reader",0).edit().putString("protected-terms",terms.getText().toString()).apply();setEditing(false);apply(source,false);});
        text(correction,"登録語は端末の設定として保存します。長い名称は表示幅に合わせ12文字ずつ表示します。",12);
        button(content,"現在の画面を読み取り直す",onRetry);
        editor.setText(selected);apply(selected,autoplay);
    }
    private void apply(String text,boolean autoplay) {
        apply(text,autoplay,0);
    }
    private void apply(String text,boolean autoplay,int offset){
        pause();finished=false;source=text;units=new ArrayList<>();index=0;pendingOffset=offset;preparing=true;requestedPlayback=autoplay;
        long generation=++textGeneration;String requestedMode=mode;String terms=context.getSharedPreferences("native-reader",0).getString("protected-terms","");render();
        textExecutor.execute(()->{
            if(generation!=textGeneration)return;
            List<String> parsed;
            try{parsed=ReadingText.tokenize(text,requestedMode,JapaneseWords.terms(terms));}
            catch(RuntimeException|LinkageError|OutOfMemoryError e){handler.post(()->{if(closed||generation!=textGeneration)return;preparing=false;requestedPlayback=false;render();status.setText("単語の準備に失敗しました。文字モードか文章の修正を試してください。");});return;}
            handler.post(()->{
                if(closed||generation!=textGeneration)return;preparing=false;units=parsed;int remaining=offset;
                while(index+1<units.size()&&remaining>=ReadingText.visibleCount(units.get(index))){remaining-=ReadingText.visibleCount(units.get(index));index++;}
                render();if(units.isEmpty())status.setText("本文を特定できませんでした。範囲か文章を確認してください。");else if(requestedPlayback)toggle();
            });
        });
    }
    private void toggle() { if(closed||units.isEmpty())return;if(playing){pause();return;}if(finished){index=0;finished=false;}playing=true;render();schedule(); }
    private void pause() { requestedPlayback=false;playing=false;handler.removeCallbacks(tick);if(play!=null)play.setText("再生"); }
    private void schedule() { handler.removeCallbacks(tick);if(playing&&!units.isEmpty())handler.postDelayed(tick,ReadingText.duration(units.get(index),speed)); }
    private void render() {
        if(word==null)return;
        word.setWord(units.isEmpty()?"":units.get(index));position.setMax(Math.max(0,units.size()-1));position.setProgress(index);
        status.setText(notice.isEmpty()?context.getString(R.string.reader_position,units.isEmpty()?0:index+1,units.size(),automatic?"自動 "+page+"画面目":playing?"本文候補を再生中":"停止中"):notice);status.setMaxLines(notice.isEmpty()?2:5);
        speedLabel.setText(context.getString(R.string.reader_speed,speed));play.setText(playing?"一時停止":"再生");
        play.setEnabled(!preparing);if(preparing)play.setText("区切りを準備中");
        previous.setEnabled(!preparing);next.setEnabled(!preparing);position.setEnabled(!preparing);
    }
    private void setEditing(boolean editing) {
        if(closed)return;
        if(editing)params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else {context.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(),0);params.flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;}
        if(root.isAttachedToWindow())manager.updateViewLayout(root,params);
    }
    @Override public void close() {
        if(closed)return;closed=true;playing=false;textGeneration++;handler.removeCallbacksAndMessages(null);
        context.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(),0);
        if(editor!=null)editor.setText("");if(word!=null)word.setWord("");source="";units.clear();
        OwnOverlayWindows.remove(root);
        if(root.isAttachedToWindow())try{manager.removeViewImmediate(root);}catch(IllegalArgumentException ignored){}
        if(preview!=null&&!preview.isRecycled())preview.recycle();preview=null;
    }
    private LinearLayout row(LinearLayout parent) { LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return row; }
    private TextView text(LinearLayout parent,String value,int size) {TextView t=new TextView(context);t.setText(value);t.setTextSize(size);t.setTextColor(Color.rgb(51,72,62));t.setPadding(dp(2),dp(5),dp(2),dp(5));parent.addView(t);return t;}
    private Button button(LinearLayout parent,String label,Runnable action){Button b=new Button(context);b.setText(label);b.setAllCaps(false);b.setTextSize(12);b.setMinHeight(dp(44));parent.addView(b);b.setOnClickListener(v->action.run());return b;}
    private Button weightedButton(LinearLayout parent,String label,Runnable action){Button b=button(parent,label,action);b.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));return b;}
    private Spinner spinner(LinearLayout parent,String[] values){Spinner s=new Spinner(context);ArrayAdapter<String> adapter=new ArrayAdapter<>(context,android.R.layout.simple_spinner_dropdown_item,values);s.setAdapter(adapter);s.setMinimumHeight(dp(44));parent.addView(s,new LinearLayout.LayoutParams(0,-2,1));return s;}
    private int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
}
