package com.ashwin.retrospaceship;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

/**
 * The single activity: a full-screen surface and nothing else.
 *
 * <p>The game is portrait-locked and immersive. A bullet-hell playfield has a
 * fixed aspect and a fixed control layout; letting it rotate would either squash
 * the field or move the buttons out from under the thumbs mid-run.
 */
public final class MainActivity extends Activity {

    private GameView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // getAttributes() returns a copy; assigning the field and dropping
            // the object never changed the window. SHORT_EDGES would also draw
            // the top-centre pause glyph through a punch-hole. Stay in the
            // safe area so that button remains on screen.
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            getWindow().setAttributes(lp);
        }
        view = new GameView(this);
        setContentView(view);
        goImmersive();
    }

    private void goImmersive() {
        view.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            goImmersive();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stopping the loop also pauses a run in progress, so backgrounding the
        // app never costs the player a life.
        view.stop();
    }

    @Override
    public void onBackPressed() {
        Game game = view.game();
        if (game != null && game.onBack()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        view.stop();
        view.releaseAudio();
        super.onDestroy();
    }
}
