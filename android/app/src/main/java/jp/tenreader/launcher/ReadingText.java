package jp.tenreader.launcher;

import android.icu.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ReadingText {
    static final int MAX_UNIT = 12;
    static List<String> graphemes(String text) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.JAPANESE);
        iterator.setText(text);
        List<String> result = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String part = text.substring(start, end);
            // Preserve newer emoji sequences on older Android ICU versions as well.
            int first = part.codePointAt(0);
            if (!result.isEmpty() && (first == 0x200D || first == 0xFE0F || first >= 0x1F3FB && first <= 0x1F3FF || result.get(result.size()-1).endsWith("\u200D"))) {
                int last = result.size()-1; result.set(last, result.get(last)+part);
            } else result.add(part);
        }
        return result;
    }

    static List<String> tokenize(String original, String mode) {
        String text = Normalizer.normalize(original, Normalizer.Form.NFC);
        List<String> result = new ArrayList<>();
        if ("character".equals(mode)) { for (String g : graphemes(text)) if (!blank(g)) result.add(g); return result; }
        BreakIterator words = BreakIterator.getWordInstance(Locale.JAPANESE);
        for (String line : text.split("[\r\n\u2028\u2029]+")) {
            words.setText(line);
            StringBuilder chunk = new StringBuilder();
            int start = words.first();
            for (int end = words.next(); end != BreakIterator.DONE; start = end, end = words.next()) {
                String unit = line.substring(start, end);
                if (blank(unit)) {if("phrase".equals(mode)&&chunk.length()>0)chunk.append(unit);continue;}
                boolean closing = unit.matches("[\\p{Pe}\\p{Pf}、。，．！？!?…,:;]+" );
                if (chunk.length() > 0 && !chunk.toString().matches("[\\p{Ps}\\p{Pi}「『]+") && !closing && ("word".equals(mode) || graphemes(chunk+unit).size() > 6)) {
                    split(result, chunk.toString()); chunk.setLength(0);
                }
                chunk.append(unit);
                if (unit.matches(".*[。！？!?]$")) { split(result, chunk.toString()); chunk.setLength(0); }
            }
            split(result, chunk.toString());
        }
        return result;
    }
    private static void split(List<String> result, String text) {
        List<String> chars = graphemes(text);
        for (int i=0; i<chars.size(); i+=MAX_UNIT) {
            String piece = strip(String.join("", chars.subList(i, Math.min(chars.size(), i+MAX_UNIT))));
            if (!blank(piece)) result.add(piece);
        }
    }
    static boolean blank(String text){return text.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c));}
    static String strip(String text){
        int start=0,end=text.length();while(start<end){int c=text.codePointAt(start);if(!Character.isWhitespace(c)&&!Character.isSpaceChar(c))break;start+=Character.charCount(c);}
        while(end>start){int c=text.codePointBefore(end);if(!Character.isWhitespace(c)&&!Character.isSpaceChar(c))break;end-=Character.charCount(c);}return text.substring(start,end);
    }
    static long duration(String text, int speed) {
        long count=visibleCount(text);
        long base = Math.max(16, Math.round(count*60000.0/Math.max(200, Math.min(6000, speed))));
        return Math.round(base * (text.matches(".*[。！？!?][」』）】]*$") ? 1.8 : text.matches(".*[、，,][」』）】]*$") ? 1.3 : 1));
    }
    static int visibleCount(String text){return (int)graphemes(text).stream().filter(g->!blank(g)).count();}
}
