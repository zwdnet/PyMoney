package com.pymoney.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.pymoney.Prefs;
import com.pymoney.R;

/** 外观：跟随系统 / 固定浅色 / 固定深色。换完立刻重建这一页，所见即所得。 */
public class AppearanceActivity extends Activity {

    private RadioGroup group;
    private TextView note;
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_appearance);
        group = (RadioGroup) findViewById(R.id.appearance_group);
        note = (TextView) findViewById(R.id.appearance_note);

        binding = true;
        group.check(checkId(new Prefs(this).themeMode()));
        binding = false;
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup radioGroup, int checkedId) {
                if (binding) {
                    return;
                }
                Prefs prefs = new Prefs(AppearanceActivity.this);
                int mode = modeOf(checkedId);
                if (mode == prefs.themeMode()) {
                    return;
                }
                prefs.setThemeMode(mode);
                Theme.apply(AppearanceActivity.this);
                // setApplicationNightMode 自己会触发重建；低版本上它什么也没做，这里补一次让提示文字跟着换
                recreate();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        note.setText(describe());
    }

    private String describe() {
        int mode = new Prefs(this).themeMode();
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (!Theme.canForce()) {
            return getString(R.string.appearance_old_system, dark ? "深色" : "浅色");
        }
        switch (mode) {
            case Prefs.THEME_LIGHT:
                return getString(R.string.appearance_now_light);
            case Prefs.THEME_DARK:
                return getString(R.string.appearance_now_dark);
            default:
                return getString(R.string.appearance_now_auto, dark ? "深色" : "浅色");
        }
    }

    private static int checkId(int mode) {
        switch (mode) {
            case Prefs.THEME_LIGHT:
                return R.id.appearance_light;
            case Prefs.THEME_DARK:
                return R.id.appearance_dark;
            default:
                return R.id.appearance_auto;
        }
    }

    private static int modeOf(int checkId) {
        if (checkId == R.id.appearance_light) {
            return Prefs.THEME_LIGHT;
        }
        if (checkId == R.id.appearance_dark) {
            return Prefs.THEME_DARK;
        }
        return Prefs.THEME_AUTO;
    }
}
