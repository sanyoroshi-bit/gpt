package jp.tenreader.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Public launcher entry; external intents cannot invoke internal overlay actions. */
public final class EntryActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
