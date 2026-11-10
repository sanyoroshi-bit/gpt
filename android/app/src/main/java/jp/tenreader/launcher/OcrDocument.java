package jp.tenreader.launcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Geometry is in captured-image pixels, never overlay/window coordinates. */
final class OcrDocument {
    static final class Line {
        final String text;
        final int left, top, right, bottom;
        Line(String text, int left, int top, int right, int bottom) {
            this.text=text; this.left=left; this.top=top; this.right=right; this.bottom=bottom;
        }
    }
    static final class Region {
        final int left, top, right, bottom;
        Region(int left,int top,int right,int bottom) { this.left=left;this.top=top;this.right=right;this.bottom=bottom; }
    }
    final int width, height;
    final List<Line> lines;
    OcrDocument(int width, int height, List<Line> lines) {
        this.width=width; this.height=height; this.lines=new ArrayList<>(lines);
    }
    Region bodyRegion() {
        // Find the dominant column, grouping aligned lines through paragraph breaks.
        List<Line> candidates = new ArrayList<>();
        for (Line line : lines) if (line.text.trim().length() >= 4 && (line.text.length()>=8 || line.top >= height*.035 && line.bottom <= height*.96)) candidates.add(line);
        if (candidates.isEmpty()) return new Region(0,0,width,height);
        List<List<Line>> columns=new ArrayList<>();
        candidates.sort(Comparator.comparingInt(l->l.left));
        for(Line line:candidates){
            List<Line> matched=null;
            for(List<Line> column:columns){
                Line seed=column.get(0);int narrow=Math.min(seed.right-seed.left,line.right-line.left);
                int overlap=Math.min(seed.right,line.right)-Math.max(seed.left,line.left);
                if(Math.abs(seed.left-line.left)<=Math.max(8,narrow*.3)&&overlap>=narrow*.6){matched=column;break;}
            }
            if(matched==null){if(columns.size()>=32)continue;matched=new ArrayList<>();columns.add(matched);}matched.add(line);
        }
        List<Line> best=new ArrayList<>();double bestScore=-1;
        for(List<Line> group:columns){double score=0;for(Line line:group){double center=(line.left+line.right)/2.0/width;score+=Math.min(120,line.text.length())*(1-Math.abs(.5-center)*.6);}if(score>bestScore){bestScore=score;best=group;}}
        int left=width,top=height,right=0,bottom=0;
        for (Line line:best) { left=Math.min(left,line.left);top=Math.min(top,line.top);right=Math.max(right,line.right);bottom=Math.max(bottom,line.bottom); }
        return new Region(Math.max(0,left-4),Math.max(0,top-4),Math.min(width,right+4),Math.min(height,bottom+4));
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
