package imane.androproject.Chatooth;

import android.content.Intent;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import imane.androproject.Chatooth.R;

public class TempoActivity extends AppCompatActivity {

    private static int SPLASH_TIME_OUT = 4000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tempo);

        new Handler().postDelayed(new Runnable(){

            @Override
            public void run(){
                Intent homeIntent = new Intent(TempoActivity.this, MainActivity.class);
                startActivity(homeIntent);
                finish();

            }


        },SPLASH_TIME_OUT) ;
    }
}
