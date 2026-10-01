package app.so7o.ztrackpad.lite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Minimal launcher screen: status + a shortcut to the accessibility settings. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);
        root.setBackgroundColor(Color.parseColor("#101014"));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);

        TextView body = new TextView(this);
        body.setText("\nA floating trackpad and pointer, with no root and no companion app.\n\n"
                + "1. Tap the button below.\n"
                + "2. Enable \"" + getString(R.string.app_name) + "\" under Installed services.\n"
                + "3. Come back here - a small dot appears on screen.\n\n"
                + "The dot\n"
                + "  \u25CF  trackpad (right edge by default) - tap to show or hide\n"
                + "  drag it and it snaps to the nearer edge\n\n"
                + "The trackpad\n"
                + "  \u2261 MOVE  drag bar at the top - move the pad, tap it to re-centre\n"
                + "  \u25D0         theme and opacity\n"
                + "  lock        freeze the pad's position and size\n"
                + "  corners     drag any corner to resize\n\n"
                + "Gestures\n"
                + "  \u2022 drag one finger - move the pointer\n"
                + "  \u2022 tap - click at the pointer, even under the pad\n"
                + "  \u2022 hold still - long press\n"
                + "  \u2022 two-finger drag - scroll\n"
                + "  \u2022 swipe along the left or right edge - scroll\n"
                + "  \u2022 \u2934 - arm press-and-drag, tap again to disarm\n\n"
                + "Clicks and swipes are delivered as accessibility gestures: that is what\n"
                + "makes this work with nothing installed alongside it, and also what it\n"
                + "costs - there is no hover, and a drag is approximate.\n");
        body.setTextColor(0xFFDDDDDD);
        body.setTextSize(14f);

        Button go = new Button(this);
        go.setText("Open Accessibility Settings");
        go.setGravity(Gravity.CENTER);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        });

        root.addView(title);
        root.addView(body);
        root.addView(go);
        setContentView(root);
    }
}
