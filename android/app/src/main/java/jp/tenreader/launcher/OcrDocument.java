package jp.tenreader.launcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Geometry is in captured-image pixels, never overlay/window coordinates. */
final class OcrDocument {
    static final class Line {
        final String text;
        final int left, top, right, bottom;
        final int block;
        final List<Line> elements;
        Line(String text, int left, int top, int right, int bottom) {
            this(text,left,top,right,bottom,-1,java.util.Collections.emptyList());
        }
        Line(String text,int left,int top,int right,int bottom,int block,List<Line> elements){
            this.text=text;this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.block=block;this.elements=new ArrayList<>(elements);
        }
    }
    static final class Region {
        final int left, top, right, bottom;
        Region(int left,int top,int right,int bottom) { this.left=left;this.top=top;this.right=right;this.bottom=bottom; }
    }
    final int width, height;
    final List<Line> lines;
    private BodyLayout.Selection selection;
    OcrDocument(int width, int height, List<Line> lines) {
        this.width=width; this.height=height; this.lines=new ArrayList<>(lines);
    }
    Region bodyRegion() {
        List<Line> best=body().lines;
        if(best.isEmpty())return new Region(0,0,width,height);
        int left=width,top=height,right=0,bottom=0;
        for (Line line:best) { left=Math.min(left,line.left);top=Math.min(top,line.top);right=Math.max(right,line.right);bottom=Math.max(bottom,line.bottom); }
        return new Region(Math.max(0,left-4),Math.max(0,top-4),Math.min(width,right+4),Math.min(height,bottom+4));
    }
    BodyLayout.Selection body(){if(selection==null)selection=BodyLayout.select(this);return selection;}
    String rawText(){return text(new Region(0,0,width,height));}
    private List<Line> rawLines(){
        List<Line> ordered=new ArrayList<>();for(Line line:lines){double x=(line.left+line.right)/2.0,y=(line.top+line.bottom)/2.0;if(x>=0&&x<=width&&y>=0&&y<=height&&!line.text.trim().isEmpty())ordered.add(line);}
        ordered.sort(Comparator.comparingInt((Line l)->l.top).thenComparingInt(l->l.left));return ordered;
    }
    String bodyAfterRawPrefix(int visiblePrefix){
        List<Line> ordered=rawLines();
        StringBuilder result=new StringBuilder();int offset=0;
        for(Line line:ordered){
            List<String> chars=ReadingText.graphemes(java.text.Normalizer.normalize(line.text.trim(),java.text.Normalizer.Form.NFC));
            boolean body=body().lines.contains(line);StringBuilder unread=new StringBuilder();
            for(String c:chars){boolean visible=!ReadingText.blank(c);if(body&&offset>=visiblePrefix)unread.append(c);if(visible)offset++;}
            if(unread.length()>0){if(result.length()>0)result.append('\n');result.append(unread);}
        }
        return result.toString();
    }
    boolean unreadIsNonBody(int visiblePrefix){
        List<Line> ordered=rawLines();int offset=0;boolean any=false;
        for(Line line:ordered){int count=ReadingText.visibleCount(line.text.trim());if(offset+count>visiblePrefix&&count>0){any=true;if(!body().excluded.contains(line))return false;}offset+=count;}
        return any;
    }
    String text(Region region) {
        List<Line> selected = new ArrayList<>();
        for (Line line:lines) {
            double x=(line.left+line.right)/2.0,y=(line.top+line.bottom)/2.0;
            if (x>=region.left && x<=region.right && y>=region.top && y<=region.bottom) selected.add(line);
        }
        selected.sort(Comparator.comparingInt((Line l)->l.top).thenComparingInt(l->l.left));
        List<String> text = new ArrayList<>(); for (Line line:selected) if (!line.text.trim().isEmpty()) text.add(line.text.trim());
        return String.join("\n",text);
    }
}
