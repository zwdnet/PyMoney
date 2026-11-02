package com.pymoney;

import android.app.Application;
import android.content.Context;

import com.pymoney.ui.Theme;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 只做一件事：把没接住的异常写进私有目录，下次打开时能在界面上看到。
 *
 * 构建机没有模拟器也没有真机，「点一下确定就回到桌面、什么提示都没有」这种问题在这边完全看不见、
 * 也复现不出来。有这份文件，安全页就能把上次异常退出的原因念给人看，而不是让人猜是不是自己点错了。
 * 只写本地文件：不联网、不弹框、不改动任何数据。
 */
public final class PyMoneyApp extends Application {

    public static final String CRASH_FILE = "crash.txt";
    private static final int KEEP = 5;
    private static final String SEPARATOR = "\n=====\n";

    @Override
    public void onCreate() {
        super.onCreate();
        // 必须赶在任何 Activity 创建之前：用户在「外观」里选的明暗要先生效，
        // 否则首页会先按系统配色画一帧再跳成用户选的那种。
        Theme.apply(this);
        final Thread.UncaughtExceptionHandler previous
                = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    appendCrash(thread, error);
                } catch (Exception ignored) {
                    // 记不下来也不能挡住系统正常收尾
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            }
        });
    }

    private void appendCrash(Thread thread, Throwable error) throws Exception {
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        out.print(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()) + " 线程 " + thread.getName() + "\n");
        error.printStackTrace(out);
        out.flush();
        List<String> rows = entries(this);
        rows.add(0, text.toString());
        while (rows.size() > KEEP) {
            rows.remove(rows.size() - 1);
        }
        FileOutputStream stream = new FileOutputStream(file(this));
        try {
            byte[] bytes = join(rows).getBytes(StandardCharsets.UTF_8);
            stream.write(bytes);
        } finally {
            stream.close();
        }
    }

    /** 最近一次异常退出的内容；没有就返回 null。文件留着不删，方便反复对照 */
    public static String latest(Context context) {
        List<String> rows = entries(context);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<String> entries(Context context) {
        List<String> rows = new ArrayList<>();
        File source = file(context);
        if (!source.exists()) {
            return rows;
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(source), StandardCharsets.UTF_8));
            try {
                StringBuilder text = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line).append('\n');
                }
                for (String part : text.toString().split(SEPARATOR)) {
                    if (part.trim().length() > 0) {
                        rows.add(part.trim());
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Exception ignored) {
            // 读不出来就当没有，别为了诊断功能自己再抛一次
        }
        return rows;
    }

    private static String join(List<String> rows) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                text.append(SEPARATOR).append('\n');
            }
            text.append(rows.get(i));
        }
        return text.toString();
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), CRASH_FILE);
    }
}
