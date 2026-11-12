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
    @Test public void titlesAndTableCellsInsideTheSameRectangleStayExcluded(){
        List<OcrDocument.Line> lines=new ArrayList<>();lines.add(new OcrDocument.Line("大きな記事タイトル",20,60,580,110));
        lines.add(new OcrDocument.Line("本文はここから始まり、図表の後にも続きます。",20,140,580,164));
        String[][] rows={{"年度","売上高"},{"2024","100億円"},{"2025","120億円"}};
        for(int i=0;i<3;i++){lines.add(new OcrDocument.Line(rows[i][0],20,200+i*30,140,222+i*30));lines.add(new OcrDocument.Line(rows[i][1],300,200+i*30,450,222+i*30));}
        lines.add(new OcrDocument.Line("表を飛ばしても、その下の本文を残します。",20,340,580,364));lines.add(new OcrDocument.Line("だ。",20,380,60,404));
        OcrDocument doc=new OcrDocument(600,800,lines);String body=doc.body().text();assertTrue(body.contains("始まり"));assertTrue(body.contains("その下"));assertTrue(body.endsWith("だ。"));assertFalse(body.contains("タイトル"));assertFalse(body.contains("100億円"));assertEquals(6,doc.body().tables.size());
        assertTrue(doc.rawText().contains("100億円"));assertTrue(doc.text(doc.bodyRegion()).contains("100億円"));
    }
    @Test public void tableOnlyViewportIsNotAnArticleEnd(){
        List<OcrDocument.Line> lines=new ArrayList<>();for(int i=0;i<4;i++){lines.add(new OcrDocument.Line("項目"+i,20,100+i*30,120,120+i*30));lines.add(new OcrDocument.Line("数値"+(i*10),220,100+i*30,350,120+i*30));}
        OcrDocument doc=new OcrDocument(400,600,lines);assertTrue(doc.body().onlyNonBody);assertTrue(doc.body().text().isEmpty());assertFalse(doc.rawText().isEmpty());
    }
    @Test public void numericProseAndSparseShortProseAreNotDeleted(){
        OcrDocument doc=new OcrDocument(600,800,List.of(new OcrDocument.Line("2026年の売上高は100億円に達した。",20,100,580,124),new OcrDocument.Line("短い文。",20,140,160,164)));
        assertTrue(doc.body().text().contains("100億円に達した"));assertTrue(doc.body().text().contains("短い文。"));assertFalse(doc.body().onlyNonBody);
    }
    @Test public void shortProseAtEitherViewportEdgeIsKeptWhileKnownMenuLabelsAreRemoved(){
        OcrDocument doc=new OcrDocument(600,1000,List.of(new OcrDocument.Line("だ。",20,1,60,21),new OcrDocument.Line("本文の途中を切り出した画面でも短い文を残します。",20,60,580,80),new OcrDocument.Line("とした。",20,978,110,998),new OcrDocument.Line("ホーム",20,26,100,46),new OcrDocument.Line("検索",400,26,460,46)));
        String text=doc.body().text();assertTrue(text,text.startsWith("だ。"));assertTrue(text,text.endsWith("とした。"));assertFalse(text.contains("ホーム"));assertFalse(text.contains("検索"));
    }
}
