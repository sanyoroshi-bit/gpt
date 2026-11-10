package jp.tenreader.launcher;

import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;

interface OcrEngine extends AutoCloseable {
    interface Callback { void complete(OcrDocument document); }
    interface Factory { OcrEngine create(); }
    void read(Bitmap bitmap,Callback callback);
    @Override void close();
}

final class JapaneseOcrEngine implements OcrEngine {
    private final TextRecognizer recognizer=TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
    @Override public void read(Bitmap bitmap,Callback callback) {
        int width=bitmap.getWidth(),height=bitmap.getHeight();
        recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnCompleteListener(task->{
            if(!task.isSuccessful()){callback.complete(null);return;}
            List<OcrDocument.Line> lines=new ArrayList<>();int characters=0;
            for(com.google.mlkit.vision.text.Text.TextBlock block:task.getResult().getTextBlocks())for(com.google.mlkit.vision.text.Text.Line line:block.getLines()) {
                Rect rect=line.getBoundingBox();String text=line.getText();characters+=text.length();
                if(rect!=null && !text.trim().isEmpty() && characters<=200000)lines.add(new OcrDocument.Line(text,rect.left,rect.top,rect.right,rect.bottom));
            }
            callback.complete(new OcrDocument(width,height,lines));
        });
    }
    @Override public void close() {recognizer.close();}
}
