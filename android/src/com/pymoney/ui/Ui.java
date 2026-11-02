package com.pymoney.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.pymoney.R;
import com.pymoney.calc.MoneyFormat;

import java.util.Calendar;

/** 一些界面小工具，避免每个页面重复写对话框。 */
public final class Ui {

    private Ui() {
    }

    public interface TextCallback {
        void onText(String text);
    }

    public interface DateCallback {
        void onDate(int year, int month, int day);
    }

    public static void toast(Context context, String message) {
        Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** 单行输入框对话框 */
    public static void input(Context context, String title, String hint, String preset,
                             boolean numeric, final TextCallback callback) {
        final EditText input = new EditText(context);
        input.setHint(hint);
        input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        input.setText(preset == null ? "" : preset);
        input.setSelection(input.getText().length());
        showInput(context, title, input, new Runnable() {
            @Override
            public void run() {
                callback.onText(input.getText().toString().trim());
            }
        });
    }

    /**
     * 口令输入框。和 input() 的区别是掩码显示 + 不回显初值：
     * 界面上任何地方都不该出现口令的明文，连"上次输过"这种提示都不给。
     */
    public static void password(Context context, String title, String hint,
                                final TextCallback callback) {
        final EditText input = new EditText(context);
        input.setHint(hint);
        CheckBox show = maskToggle(context, input);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(input);
        box.addView(show);
        showInput(context, title, box, new Runnable() {
            @Override
            public void run() {
                // 口令不 trim：前后空格可能就是口令的一部分，交给派生函数原样算
                callback.onText(input.getText().toString());
            }
        });
    }

    /**
     * 给口令框装掩码，外加一个「显示口令」勾选框（默认不勾，想核对时自己勾）。
     *
     * 光设 TYPE_TEXT_VARIATION_PASSWORD 不够：澎湃OS 会按系统里的「显示密码」
     * 把输入内容直接回显成明文，所以这里再显式装一次 PasswordTransformationMethod。
     */
    public static CheckBox maskToggle(Context context, final EditText input) {
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        CheckBox show = new CheckBox(context);
        show.setText(R.string.show_pass);
        show.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                input.setTransformationMethod(isChecked ? null
                        : PasswordTransformationMethod.getInstance());
                input.setSelection(input.getText().length());
            }
        });
        return show;
    }

    private static void showInput(Context context, String title, View content,
                                  final Runnable confirm) {
        int padding = dp(context, 20);
        android.widget.FrameLayout wrapper = new android.widget.FrameLayout(context);
        wrapper.setPadding(padding, padding / 2, padding, 0);
        wrapper.addView(content);
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(wrapper)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        confirm.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 确认框 */
    public static void confirm(Context context, String title, String message, final Runnable yes) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        yes.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 日期选择器，initial 是 YYYYMMDD */
    public static void pickDate(Context context, int initial, final DateCallback callback) {
        int year = initial / 10000;
        int month = (initial / 100) % 100;
        int day = initial % 100;
        if (!MoneyFormat.isValidDate(initial)) {
            Calendar now = Calendar.getInstance();
            year = now.get(Calendar.YEAR);
            month = now.get(Calendar.MONTH) + 1;
            day = now.get(Calendar.DAY_OF_MONTH);
        }
        DatePickerDialog dialog = new DatePickerDialog(context,
                new DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(DatePicker view, int y, int m, int d) {
                        callback.onDate(y, m + 1, d);
                    }
                }, year, month - 1, day);
        dialog.show();
    }

    /** 列表选择框，labels 与回调下标对应 */
    public static void choose(Context context, String title, String[] labels,
                              final TextCallback callback) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        callback.onText(String.valueOf(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
