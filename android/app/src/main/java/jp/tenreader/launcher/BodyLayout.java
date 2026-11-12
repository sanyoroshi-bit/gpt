package jp.tenreader.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Selects actual lines, never re-includes rejected table/title lines through a bounding rectangle. */
final class BodyLayout {
    static final class Selection {
        final List<OcrDocument.Line> lines;
        final Set<OcrDocument.Line> tables,headings;
        final Set<OcrDocument.Line> excluded;
        final boolean onlyNonBody;
        Selection(List<OcrDocument.Line> lines,Set<OcrDocument.Line> tables,Set<OcrDocument.Line> headings,Set<OcrDocument.Line> excluded,boolean onlyNonBody){this.lines=lines;this.tables=tables;this.headings=headings;this.excluded=excluded;this.onlyNonBody=onlyNonBody;}
        String text(){List<String> parts=new ArrayList<>();for(OcrDocument.Line l:lines)parts.add(l.text.trim());return String.join("\n",parts);}
    }
    private static int length(String text){String compact=text.replaceAll("\\s","");return compact.codePointCount(0,compact.length());}
    private static boolean prose(OcrDocument.Line l){return length(l.text)>=12&&(l.text.matches(".*[、。！？!?].*")||length(l.text)>=18);}
    private static boolean numeric(String text){return text.matches(".*[0-9０-９].*");}
    static Selection select(OcrDocument doc){
        List<OcrDocument.Line> all=new ArrayList<>();for(OcrDocument.Line l:doc.lines)if(l.right>l.left&&l.bottom>l.top&&!l.text.trim().isEmpty()&&(l.left+l.right)/2.0>=0&&(l.left+l.right)/2.0<=doc.width&&(l.top+l.bottom)/2.0>=0&&(l.top+l.bottom)/2.0<=doc.height)all.add(l);
        all.sort(Comparator.comparingInt((OcrDocument.Line l)->l.top).thenComparingInt(l->l.left));
        List<Integer> heights=new ArrayList<>();boolean punctuated=false;for(OcrDocument.Line l:all)if(prose(l)&&l.text.matches(".*[、。].*"))punctuated=true;
        for(OcrDocument.Line l:all)if(prose(l)&&(!punctuated||l.text.matches(".*[、。].*")))for(int n=0;n<Math.min(60,length(l.text));n++)heights.add(l.bottom-l.top);
        if(heights.isEmpty())for(OcrDocument.Line l:all)heights.add(l.bottom-l.top);
        Collections.sort(heights);int font=heights.isEmpty()?20:Math.max(1,heights.get(heights.size()/2));
        Set<OcrDocument.Line> tables=tableLines(all,font),headings=new HashSet<>(),noise=new HashSet<>();
        boolean hasProse=false;for(OcrDocument.Line l:all)if(!tables.contains(l)&&prose(l)&&l.bottom-l.top<=font*1.35)hasProse=true;
        for(OcrDocument.Line l:all){
            String t=l.text.trim();
            if(hasProse&&(l.bottom-l.top>font*1.45||t.matches("^(?:図|表|写真|出所|注)[\\s:：0-9０-９].*")&&l.bottom-l.top<font*.95))headings.add(l);
            if(t.matches("^(?:日経電子版|日本経済新聞|ホーム|ニュース|検索|メニュー|アプリメニュー|関連記事|おすすめ記事|記事をシェア|広告|保存|戻る)$"))noise.add(l);
        }
        List<List<OcrDocument.Line>> columns=new ArrayList<>();
        for(OcrDocument.Line l:all){
            if(tables.contains(l)||headings.contains(l)||noise.contains(l)||!prose(l))continue;
            List<OcrDocument.Line> found=null;
            for(List<OcrDocument.Line> c:columns){OcrDocument.Line seed=c.get(0);if(Math.abs(seed.left-l.left)<=font*2&&Math.min(seed.right,l.right)-Math.max(seed.left,l.left)>=Math.min(seed.right-seed.left,l.right-l.left)*.6){found=c;break;}}
            if(found==null){if(columns.size()>=32)continue;found=new ArrayList<>();columns.add(found);}found.add(l);
        }
        List<OcrDocument.Line> main=new ArrayList<>();double best=-1;
        for(List<OcrDocument.Line> c:columns){double score=0;for(OcrDocument.Line l:c)score+=Math.min(120,length(l.text))*(1-Math.abs(.5-(l.left+l.right)/2.0/Math.max(1,doc.width))*.6);if(score>best){best=score;main=c;}}
        List<OcrDocument.Line> selected=new ArrayList<>();
        if(!main.isEmpty()){
            int left=doc.width,right=0;for(OcrDocument.Line l:main){left=Math.min(left,l.left);right=Math.max(right,l.right);}
            for(OcrDocument.Line l:all){
                if(tables.contains(l)||headings.contains(l)||noise.contains(l))continue;
                double overlap=Math.min(right,l.right)-Math.max(left,l.left);
                if(l.bottom-l.top>=font*.65&&l.bottom-l.top<=font*1.4&&((Math.abs(l.left-left)<=font*2&&overlap>=Math.min(right-left,l.right-l.left)*.5)))selected.add(l);
            }
        }else{
            // Sparse short prose remains available for confirmation instead of disappearing.
            for(OcrDocument.Line l:all)if(!tables.contains(l)&&!headings.contains(l)&&!noise.contains(l))selected.add(l);
        }
        boolean onlyNonBody=selected.isEmpty()&&!tables.isEmpty();
        Set<OcrDocument.Line> excluded=new HashSet<>(tables);excluded.addAll(headings);excluded.addAll(noise);
        return new Selection(selected,tables,headings,excluded,onlyNonBody);
    }
    private static final class Cell {final OcrDocument.Line owner,box;Cell(OcrDocument.Line owner,OcrDocument.Line box){this.owner=owner;this.box=box;}}
    private static Set<OcrDocument.Line> tableLines(List<OcrDocument.Line> lines,int font){
        List<List<Cell>> rows=new ArrayList<>();
        for(OcrDocument.Line line:lines){
            List<OcrDocument.Line> cells=new ArrayList<>();
            if(line.elements.size()>=2){List<OcrDocument.Line> elements=new ArrayList<>(line.elements);elements.sort(Comparator.comparingInt(l->l.left));boolean gap=false;for(int i=1;i<elements.size();i++)if(elements.get(i).left-elements.get(i-1).right>font*1.25)gap=true;if(gap)cells.addAll(elements);}
            if(cells.isEmpty())cells.add(line);
            for(OcrDocument.Line box:cells){
                if(length(box.text)>24||box.text.matches(".*[。！？!?].*"))continue;
                List<Cell> row=null;double center=(box.top+box.bottom)/2.0;
                for(List<Cell> r:rows)if(Math.abs(center-(r.get(0).box.top+r.get(0).box.bottom)/2.0)<=font*.6){row=r;break;}
                if(row==null){row=new ArrayList<>();rows.add(row);}row.add(new Cell(line,box));
            }
        }
        rows.sort(Comparator.comparingInt(r->r.get(0).box.top));for(List<Cell> row:rows)row.sort(Comparator.comparingInt(c->c.box.left));
        Set<OcrDocument.Line> result=new HashSet<>();
        for(int start=0;start+2<rows.size();start++){
            List<Cell> first=rows.get(start);if(first.size()<2)continue;int end=start+1,numericRows=0;
            for(Cell c:first)if(numeric(c.box.text)){numericRows++;break;}
            while(end<rows.size()){
                List<Cell> row=rows.get(end),previous=rows.get(end-1);if(row.size()!=first.size()||row.get(0).box.top-previous.get(0).box.top>font*3.5)break;
                boolean aligned=true;for(int i=0;i<row.size();i++)if(Math.abs(row.get(i).box.left-first.get(i).box.left)>font*.9)aligned=false;
                if(!aligned)break;for(Cell c:row)if(numeric(c.box.text)){numericRows++;break;}end++;
            }
            if(end-start>=3&&numericRows>=2){for(int r=start;r<end;r++)for(Cell c:rows.get(r))result.add(c.owner);start=end-1;}
        }
        return result;
    }
}
