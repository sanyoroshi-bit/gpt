package jp.tenreader.launcher;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Offline NORMAL-mode morphology; protected surfaces take precedence over dictionary boundaries. */
final class JapaneseWords {
    private static final class Dictionary {static final Tokenizer tokenizer=new Tokenizer.Builder().mode(Tokenizer.Mode.NORMAL).build();}
    static List<String> terms(String input){
        List<String> result=new ArrayList<>();for(String line:Normalizer.normalize(input,Normalizer.Form.NFC).split("[\\r\\n]+")){String word=ReadingText.strip(line);if(!word.isEmpty()&&word.codePointCount(0,word.length())<=80&&!result.contains(word))result.add(word);if(result.size()>=100)break;}
        result.sort(Comparator.comparingInt(String::length).reversed());return result;
    }
    static List<String> split(String line,List<String> protectedTerms){
        List<String> result=new ArrayList<>();int start=0,position=0;
        while(position<line.length()){
            String found=null;for(String term:protectedTerms)if(line.startsWith(term,position)&&wholeLatinWord(line,position,term)){found=term;break;}
            if(found==null){position+=Character.charCount(line.codePointAt(position));continue;}
            if(position>start)analyze(line.substring(start,position),result);
            result.add(found);position+=found.length();start=position;
        }
        if(start<line.length())analyze(line.substring(start),result);return result;
    }
    private static boolean latinPart(int c){return Character.UnicodeScript.of(c)==Character.UnicodeScript.LATIN||Character.isDigit(c)||c=='_';}
    private static boolean wholeLatinWord(String line,int start,String term){
        int end=start+term.length();return !(start>0&&latinPart(term.codePointAt(0))&&latinPart(line.codePointBefore(start)))&&!(end<line.length()&&latinPart(term.codePointBefore(term.length()))&&latinPart(line.codePointAt(end)));
    }
    private static boolean noun(Token token){return "名詞".equals(token.getPartOfSpeechLevel1())&&!"代名詞".equals(token.getPartOfSpeechLevel2())&&!"数".equals(token.getPartOfSpeechLevel2());}
    private static boolean proper(Token token){return "固有名詞".equals(token.getPartOfSpeechLevel2());}
    private static boolean person(Token token){return proper(token)&&"人名".equals(token.getPartOfSpeechLevel3());}
    private static boolean organizationEnd(Token token){return token.getSurface().matches("大学|銀行|新聞|新聞社|製作所|株式会社|グループ|ホールディングス|研究所|病院|高校|高等学校");}
    private static void analyze(String text,List<String> result){
        try{
            List<String> parsed=new ArrayList<>();
            List<Token> tokens=Dictionary.tokenizer.tokenize(text);int cursor=0;
            for(int i=0;i<tokens.size();i++){
                Token token=tokens.get(i);int end=token.getPosition()+token.getSurface().length(),last=i;
                boolean hasProper=proper(token);
                for(int j=i+1;j<tokens.size()&&j<=i+8;j++){
                    Token next=tokens.get(j),previous=tokens.get(j-1);
                    if(next.getPosition()!=previous.getPosition()+previous.getSurface().length())break;
                    if("・".equals(next.getSurface())&&j+1<tokens.size()){
                        Token following=tokens.get(j+1);
                        if(token.getSurface().matches("[\\p{IsKatakana}ー]+")&&noun(previous)&&noun(following)&&previous.getSurface().matches("[\\p{IsKatakana}ー]+")&&following.getSurface().matches("[\\p{IsKatakana}ー]+")&&following.getPosition()==next.getPosition()+1){last=++j;end=following.getPosition()+following.getSurface().length();continue;}
                    }
                    if(person(token)&&person(next)){last=j;end=next.getPosition()+next.getSurface().length();continue;}
                    if(!noun(token)||!noun(next))break;
                    hasProper|=proper(next);
                    if(hasProper&&organizationEnd(next)){last=j;end=next.getPosition()+next.getSurface().length();}
                }
                // Always slice the original surface, including gaps; never replace it with readings.
                if(token.getPosition()>cursor)parsed.add(text.substring(cursor,token.getPosition()));
                parsed.add(text.substring(token.getPosition(),end));cursor=end;i=last;
            }
            if(cursor<text.length())parsed.add(text.substring(cursor));result.addAll(parsed);
        }catch(RuntimeException|LinkageError e){
            // A dictionary failure must not drop text or make the reader unusable.
            result.add(text);
        }
    }
}
