package jp.tenreader.launcher;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class OcrDocumentTest {
    @Test public void bodyCandidatePrefersParagraphColumnOverNavigationAndSidebar(){
        List<OcrDocument.Line> lines=new ArrayList<>();
        lines.add(new OcrDocument.Line("アプリメニュー",20,2,500,20));lines.add(new OcrDocument.Line("広告はこちら",800,100,1000,120));
        for(int i=0;i<8;i++)lines.add(new OcrDocument.Line("本文の文章を順番に読み取ります。段落を保ちます。",50,100+i*35,700,125+i*35));
        OcrDocument doc=new OcrDocument(1080,2000,lines);String selected=doc.text(doc.bodyRegion());
        assertTrue(selected.contains("本文"));assertFalse(selected.contains("メニュー"));assertFalse(selected.contains("広告"));
    }
    @Test public void manualRegionUsesImageCoordinatesAndRestoresAllText(){
        OcrDocument doc=new OcrDocument(400,800,List.of(new OcrDocument.Line("上の文章",10,50,390,90),new OcrDocument.Line("下の文章",10,500,390,540)));
        assertEquals("下の文章",doc.text(new OcrDocument.Region(0,400,400,800)));assertEquals("上の文章\n下の文章",doc.text(new OcrDocument.Region(0,0,400,800)));
    }
    @Test public void emptyOcrDoesNotInventText(){OcrDocument doc=new OcrDocument(100,200,List.of());assertEquals("",doc.text(doc.bodyRegion()));}
}
