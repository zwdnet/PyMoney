package com.pymoney;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.IOException;

/**
 * 「备份往哪儿写」这件事的三种答案。
 * 抽出来的原因：自动备份（无人在场）和手动备份（用户点按钮）对位置的要求不一样——
 * 「每次问我」根本没法自动执行，所以能不能进自动备份的候选，得由 target 自己回答。
 */
public abstract class BackupTarget {

    /** Download/PyMoney，走 MediaStore，0 权限，也是升级前的老行为 */
    public static final int MODE_MEDIA = 0;
    /** 用户用 SAF 选一个目录，授权长期有效，可以无人值守 */
    public static final int MODE_TREE = 1;
    /** 每次点「立即备份」弹系统保存框，只能人工触发 */
    public static final int MODE_SINGLE = 2;

    public static final String LABEL_MEDIA = "media";
    public static final String LABEL_TREE = "tree";
    public static final String LABEL_SINGLE = "single";

    /** 这个位置现在能不能写（tree 被撤销授权就是 false） */
    public abstract boolean available(Context context);

    /** 能不能在没人在场时备份 */
    public abstract boolean unattended();

    public abstract String label();

    /** 给用户看的位置，尽量是真的能照着找的位置 */
    public abstract String describe(Context context);

    /** 把 source 写到这个位置，返回能回读到的真实位置 */
    public abstract Storage.Saved write(Context context, String name, String mime, File source)
            throws IOException;

    public static BackupTarget create(Prefs prefs) {
        int mode = prefs.backupTargetMode();
        if (mode == MODE_TREE) {
            String text = prefs.backupTreeUri();
            if (text == null) {
                return new Media();
            }
            return new Tree(prefs, Uri.parse(text));
        }
        if (mode == MODE_SINGLE) {
            return new EveryTime(prefs);
        }
        return new Media();
    }

    /** 这台机器上允许出现哪些位置：老系统没有可用的持久授权，别把它列出来 */
    public static boolean supportsTree() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    // ---------- 三个实现 ----------

    /** Download/PyMoney */
    public static final class Media extends BackupTarget {

        @Override
        public boolean available(Context context) {
            return true;
        }

        @Override
        public boolean unattended() {
            return true;
        }

        @Override
        public String label() {
            return LABEL_MEDIA;
        }

        @Override
        public String describe(Context context) {
            // 位置文案要能一眼看出是哪种模式：选了「Download」当自选目录时，
            // 光显示 Download/PyMoney 和默认模式一模一样，用户只会以为没生效
            return "默认 " + Storage.downloadDirHint() + "（系统媒体库）";
        }

        @Override
        public Storage.Saved write(Context context, String name, String mime, File source)
                throws IOException {
            return Storage.saveDownload(context, name, mime, source);
        }
    }

    /** 用户自选的 SAF 目录，实际写它下面的 PyMoney 子目录 */
    public static final class Tree extends BackupTarget {

        private final Prefs prefs;
        private final Uri treeUri;

        Tree(Prefs prefs, Uri treeUri) {
            this.prefs = prefs;
            this.treeUri = treeUri;
        }

        public Uri uri() {
            return treeUri;
        }

        @Override
        public boolean available(Context context) {
            return treeUri != null && Storage.treeStillGranted(context, treeUri);
        }

        @Override
        public boolean unattended() {
            return true;
        }

        @Override
        public String label() {
            return LABEL_TREE;
        }

        @Override
        public String describe(Context context) {
            String saved = prefs.backupDirLabel();
            if (saved == null || saved.length() == 0) {
                saved = Storage.treeLabel(context, treeUri);
            }
            return "自选文件夹 " + saved;
        }

        @Override
        public Storage.Saved write(Context context, String name, String mime, File source)
                throws IOException {
            if (!available(context)) {
                throw new IOException("对「" + describe(context) + "」的授权已经失效，请重新选目录");
            }
            return Storage.saveViaTree(context, treeUri, name, mime, source);
        }
    }

    /** 每次问用户，只在人点的「立即备份」里可用 */
    public static final class EveryTime extends BackupTarget {

        private final Prefs prefs;

        EveryTime(Prefs prefs) {
            this.prefs = prefs;
        }

        @Override
        public boolean available(Context context) {
            return true;
        }

        @Override
        public boolean unattended() {
            return false;
        }

        @Override
        public String label() {
            return LABEL_SINGLE;
        }

        @Override
        public String describe(Context context) {
            String last = prefs.backupDirLabel();
            return last == null || last.length() == 0
                    ? "每次点「立即备份」时由系统保存框决定"
                    : "每次问我（上次写到 " + last + "）";
        }

        @Override
        public Storage.Saved write(Context context, String name, String mime, File source)
                throws IOException {
            throw new IOException("「每次问我」不能自动执行，请在数据页点「立即备份」");
        }
    }
}
