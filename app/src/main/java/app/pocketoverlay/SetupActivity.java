package app.pocketoverlay;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class SetupActivity extends Activity {
    private Button accessibilityButton;
    private Button brightnessButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(28);
        content.addView(title);

        TextView intro = new TextView(this);
        intro.setText(R.string.setup_intro);
        content.addView(intro);

        TextView shortcuts = new TextView(this);
        shortcuts.setText(R.string.shortcut_list);
        shortcuts.setPadding(0, padding / 2, 0, padding / 2);
        content.addView(shortcuts);

        accessibilityButton = new Button(this);
        accessibilityButton.setOnClickListener(view ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        content.addView(accessibilityButton);

        brightnessButton = new Button(this);
        brightnessButton.setOnClickListener(view -> startActivity(new Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))));
        content.addView(brightnessButton);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        ComponentName service = new ComponentName(this, ShortcutService.class);
        boolean enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK).stream().anyMatch(info ->
                service.equals(ComponentName.unflattenFromString(info.getId())));
        accessibilityButton.setText(enabled
                ? R.string.accessibility_enabled : R.string.accessibility_open);
        brightnessButton.setText(Settings.System.canWrite(this)
                ? R.string.brightness_enabled : R.string.brightness_open);
    }
}
