package fir.tube;

import android.app.Activity;
import android.content.Intent;

public class BackActivity extends Activity {

    @Override
    protected void onResume() {
        super.onResume();
        Intent i = new Intent(this, MainActivity.class);
        startActivity(i);
    }   
        
}
