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
    private final Context context;
    private final WindowManager manager;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable onClose, onRetry;
    private final LinearLayout root,content;
    private final WindowManager.LayoutParams params;
    private FocusWordView word;
    private TextView status,speedLabel;
    private Button play;
    private SeekBar position;
    private EditText editor;
    private Bitmap preview;
    private List<String> units=new ArrayList<>();
    private int index,speed=600;
    private String mode="phrase",source="";
    private boolean playing,closed;
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if (closed || !playing) return;
            if (index+1>=units.size()) { pause();status.setText("読み終わりました。閉じると元の画面に戻れます。");return; }
            index++;render();schedule();
        }
    };

    ReaderOverlay(Context service,Runnable onClose,Runnable onRetry) {
        this.context=OverlayWindows.context(service);this.onClose=onClose;this.onRetry=onRetry;
        manager=context.getSystemService(WindowManager.class);
        speed=context.getSharedPreferences("native-reader",Context.MODE_PRIVATE).getInt("speed",600);
        speed=Math.max(200,Math.min(6000,speed));
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
        layout();showLoading();manager.addView(root,params);
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
    void showLoading() { content.removeAllViews();text(content,"画面の文字を読み取っています…",16);text(content,"この画面の「閉じる」で中止できます。",12); }
    void showError(String message) {
        if (closed) return;pause();content.removeAllViews();text(content,message,15);button(content,"画面を読み取り直す",onRetry);
    }
    void showDocument(OcrDocument document,Bitmap preview) {
        if (closed) { if(preview!=null)preview.recycle();return; }
        this.preview=preview;
        content.removeAllViews();
        status=text(content,"本文候補を選びました。",12);status.setId(R.id.reader_status);status.setLines(2);
        word=new FocusWordView(context);word.setId(R.id.reader_word);content.addView(word,new LinearLayout.LayoutParams(-1,dp(116)));
        position=new SeekBar(context);position.setContentDescription("読む位置");content.addView(position);
        position.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){ if(fromUser){pause();index=progress;render();} }
            @Override public void onStartTrackingTouch(SeekBar bar) { pause(); }
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout controls=row(content);
        weightedButton(controls,"戻る",()->{pause();index=Math.max(0,index-5);render();});
        play=weightedButton(controls,"再生",this::toggle);play.setId(R.id.reader_play);
        Button next=weightedButton(controls,"進む",()->{pause();index=Math.min(Math.max(0,units.size()-1),index+5);render();});next.setId(R.id.reader_next);
        speedLabel=text(content,"",12);
        SeekBar speedBar=new SeekBar(context);speedBar.setMax(116);speedBar.setProgress((speed-200)/50);speedBar.setContentDescription("読む速度");content.addView(speedBar);
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser){ if(fromUser){speed=200+value*50;context.getSharedPreferences("native-reader",0).edit().putInt("speed",speed).apply();render();if(playing)schedule();} }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        LinearLayout options=row(content);
        Spinner modes=spinner(options,new String[]{"意味のまとまり","単語","一文字"});modes.setId(R.id.reader_mode);
        modes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int selected,long id){
                String next=new String[]{"phrase","word","character"}[selected];if(next.equals(mode))return;
                int offset=0;for(int i=0;i<index;i++)offset+=ReadingText.visibleCount(units.get(i));
                pause();mode=next;units=ReadingText.tokenize(source,mode);index=0;
                while(index+1<units.size() && offset>=ReadingText.visibleCount(units.get(index))){offset-=ReadingText.visibleCount(units.get(index));index++;}render();
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        Spinner font=spinner(options,new String[]{"標準","明朝"});font.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> p,View view,int value,long id){word.setSerif(value==1);}
            @Override public void onNothingSelected(AdapterView<?> p){}
        });
        LinearLayout correction=new LinearLayout(context);correction.setOrientation(LinearLayout.VERTICAL);correction.setVisibility(View.GONE);
        button(content,"範囲・文章・文字サイズを直す",()->{pause();correction.setVisibility(correction.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);if(correction.getVisibility()==View.GONE)setEditing(false);});
        content.addView(correction);
        text(correction,"枠を描いて「範囲を適用」、または文章欄を修正してください。",12);
        OcrDocument.Region body=document.bodyRegion();
        CropPreview crop=new CropPreview(context,preview,document,body);crop.setId(R.id.reader_crop);correction.addView(crop,new LinearLayout.LayoutParams(-1,dp(200)));
        editor=new EditText(context);editor.setId(R.id.reader_editor);editor.setMinLines(2);editor.setMaxLines(4);editor.setTextSize(14);editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200000)});editor.setContentDescription("読み取った本文を修正");correction.addView(editor,new LinearLayout.LayoutParams(-1,-2));
        button(correction,"文章を編集する",()->{setEditing(true);editor.requestFocus();editor.postDelayed(()->{if(!closed)context.getSystemService(InputMethodManager.class).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);},100);});
        LinearLayout ranges=row(correction);
        weightedButton(ranges,"範囲を適用",()->{String selected=document.text(crop.region());editor.setText(selected);apply(selected,false);});
        weightedButton(ranges,"全文に戻す",()->{String all=document.text(new OcrDocument.Region(0,0,document.width,document.height));editor.setText(all);apply(all,false);});
        Button apply=button(correction,"修正を反映して再生",()->{apply(editor.getText().toString(),true);setEditing(false);correction.setVisibility(View.GONE);});apply.setId(R.id.reader_apply);
        text(correction,"文字サイズ（20〜64）",12);
        SeekBar size=new SeekBar(context);size.setMax(22);size.setProgress(8);size.setContentDescription("文字サイズ");correction.addView(size);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser){word.setSize(20+value*2);}
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        button(content,"現在の画面を読み取り直す",onRetry);
        String selected=document.text(body);editor.setText(selected);apply(selected,ReadingText.graphemes(selected).size()>=12);
    }
    private void apply(String text,boolean autoplay) {
        pause();source=text;units=ReadingText.tokenize(text,mode);index=0;render();
        if (units.isEmpty()) status.setText("範囲に文字がありません。範囲か文章を修正してください。");
        else if(autoplay) toggle();
    }
    private void toggle() { if(closed||units.isEmpty())return;if(playing){pause();return;}if(index+1==units.size())index=0;playing=true;render();schedule(); }
    private void pause() { playing=false;handler.removeCallbacks(tick);if(play!=null)play.setText("再生"); }
    private void schedule() { handler.removeCallbacks(tick);if(playing&&!units.isEmpty())handler.postDelayed(tick,ReadingText.duration(units.get(index),speed)); }
    private void render() {
        if(word==null)return;
        word.setWord(units.isEmpty()?"":units.get(index));position.setMax(Math.max(0,units.size()-1));position.setProgress(index);
        status.setText(context.getString(R.string.reader_position,units.isEmpty()?0:index+1,units.size(),playing?"本文候補を再生中":"停止中"));
        speedLabel.setText(context.getString(R.string.reader_speed,speed));play.setText(playing?"一時停止":"再生");
    }
    private void setEditing(boolean editing) {
        if(closed)return;
        if(editing)params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else {context.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(),0);params.flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;}
        if(root.isAttachedToWindow())manager.updateViewLayout(root,params);
    }
    @Override public void close() {
        if(closed)return;closed=true;playing=false;handler.removeCallbacksAndMessages(null);
        context.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(),0);
        if(editor!=null)editor.setText("");if(word!=null)word.setWord("");source="";units.clear();
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
