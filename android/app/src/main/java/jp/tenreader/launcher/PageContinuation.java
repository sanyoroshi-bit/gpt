package jp.tenreader.launcher;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Conservative suffix/prefix match: uncertainty keeps the entire next text for review. */
final class PageContinuation {
    static final class Result {
        final String text;final boolean certain,exhausted;
        Result(String text,boolean certain,boolean exhausted){this.text=text;this.certain=certain;this.exhausted=exhausted;}
    }
    static Result next(String previous,String next){
        String normalized=Normalizer.normalize(next,Normalizer.Form.NFC);
        List<String> before=visible(previous),after=new ArrayList<>();List<Integer> ends=new ArrayList<>();int offset=0;
        for(String g:ReadingText.graphemes(normalized)){offset+=g.length();if(!ReadingText.blank(g)){after.add(g);ends.add(offset);}}
        if(after.isEmpty()||before.equals(after))return new Result("",true,true);
        int max=Math.min(512,after.size()),matched=0;int[] prefix=new int[max];
        for(int i=1;i<max;i++){int j=prefix[i-1];while(j>0&&!after.get(i).equals(after.get(j)))j=prefix[j-1];if(after.get(i).equals(after.get(j)))j++;prefix[i]=j;}
        for(int i=Math.max(0,before.size()-512);i<before.size();i++){
            if(matched==max)matched=prefix[matched-1];
            while(matched>0&&!before.get(i).equals(after.get(matched)))matched=prefix[matched-1];
            if(before.get(i).equals(after.get(matched)))matched++;
        }
        if(matched==after.size())return new Result("",true,true);
        if(matched<12)return new Result(normalized,false,false);
        return new Result(ReadingText.strip(normalized.substring(ends.get(matched-1))),true,false);
    }
    static List<String> visible(String text){List<String> result=new ArrayList<>();for(String g:ReadingText.graphemes(Normalizer.normalize(text,Normalizer.Form.NFC)))if(!ReadingText.blank(g))result.add(g);return result;}
}
