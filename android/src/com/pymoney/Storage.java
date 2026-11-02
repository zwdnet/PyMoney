package com.pymoney;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 往公共存储写文件。三条路：
 * 1) MediaStore 的 Download/PyMoney（默认，Android 10 以上不需要任何存储权限）；
 * 2) 用户用 SAF 自选的目录（一次授权长期有效，同样写进它下面的 PyMoney 子目录）；
 * 3) 单次「另存为」拿到的那个 uri，直接覆写。
 * 共同规矩：同名文件优先原地覆写（openOutputStream 的 "wt" 模式），不再「删了重建」——
 * 删不掉时 MediaStore 会静默改名叫 money.db (1)，用户 cp 走的还是旧那一份。
 * 返回值一律带上写完能回读到的真实位置，不再拿字符串拼一个好看的路径。
 */
public final class Storage {

    public static final String SUBDIR = "PyMoney";

    private static final String TAG = "PyMoneyStorage";

    private Storage() {
    }

    /** 一次写入的结果：uri 可能为 null（老系统的裸文件），path 一定是能念给人听的位置 */
    public static final class Saved {
        public final Uri uri;
        public final String path;
        public final long bytes;
        /** MediaStore 那条路上的 relative_path，回读时按它重新找行；裸文件/SAF 是 null */
        public final String relative;

        Saved(Uri uri, String path, long bytes) {
            this(uri, path, bytes, null);
        }

        Saved(Uri uri, String path, long bytes, String relative) {
            this.uri = uri;
            this.path = path;
            this.bytes = bytes;
            this.relative = relative;
        }
    }

    private static boolean mediaStore() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /** 老系统上是否还缺存储权限 */
    public static boolean needsWritePermission() {
        return !mediaStore();
    }

    /** 下载目录那个位置的给人看的写法 */
    public static String downloadDirHint() {
        if (mediaStore()) {
            return Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR;
        }
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), SUBDIR).getAbsolutePath();
    }

    // ---------- 下载目录 ----------

    /**
     * 写到 Download/PyMoney/文件名。
     *
     * 一次只赌一条路是不行的：澎湃 OS 这类定制 provider 会在「覆写已有行」「IS_PENDING 新建」
     * 「Files 集合」里某一条上拒绝，而界面只能显示最终那句异常。所以这里把能走的路都走一遍，
     * 每条路的失败原因都攒起来，最后一起报出来——用户能看到到底哪一步、什么异常。
     */
    public static Saved saveDownload(Context context, String name, String mime, File source)
            throws IOException {
        if (!mediaStore()) {
            return saveLegacyDownload(context, name, mime, source);
        }
        String relative = Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR + "/";
        List<String> reasons = new ArrayList<>();
        Uri[] collections = new Uri[]{
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                MediaStore.Files.getContentUri("external")};
        String[] tags = new String[]{"Downloads", "Files"};
        for (int i = 0; i < collections.length; i++) {
            try {
                return writeViaMedia(context, collections[i], tags[i], name, mime, relative, source,
                        reasons);
            } catch (Exception e) {
                reasons.add(tags[i] + "：" + describe(e));
            }
        }
        throw new IOException("写不进 " + relative + "（" + join(reasons, "；") + "）");
    }

    /** 同一个集合里再分三种走法：覆写自有行 → 带 IS_PENDING 新建 → 不带 IS_PENDING 新建 */
    private static Saved writeViaMedia(Context context, Uri collection, String tag, String name,
            String mime, String relative, File source, List<String> reasons) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String owner = context.getPackageName();
        List<Uri> rows = queryMediaRows(resolver, collection, name, relative, owner);
        if (!rows.isEmpty()) {
            Uri target = rows.get(0);
            try {
                long written = pipe(source, resolver.openOutputStream(target, "wt"));
                deleteStaleRows(resolver, owner, rows, target);
                return new Saved(target, located(relative, target, resolver, name), written,
                        relative);
            } catch (Exception e) {
                // 覆写不成（行读不到写句柄、provider 不让写）就退回新建，但原因要留下
                reasons.add(tag + " 覆写已有文件失败：" + describe(e));
            }
        }
        try {
            return insertAndWrite(resolver, collection, name, mime, relative, source, true);
        } catch (Exception e) {
            reasons.add(tag + " 带 IS_PENDING 新建失败：" + describe(e));
        }
        return insertAndWrite(resolver, collection, name, mime, relative, source, false);
    }

    private static Saved insertAndWrite(ContentResolver resolver, Uri collection, String name,
            String mime, String relative, File source, boolean pending) throws IOException {
        Uri created = insertPending(resolver, collection, name, mime, relative, pending);
        try {
            long written = pipe(source, resolver.openOutputStream(created, "wt"));
            if (pending) {
                finishPending(resolver, created);
            }
            return new Saved(created, located(relative, created, resolver, name), written,
                    relative);
        } catch (IOException io) {
            abandon(resolver, created);
            throw io;
        } catch (RuntimeException rt) {
            abandon(resolver, created);
            throw rt;
        }
    }

    private static Saved saveLegacyDownload(Context context, String name, String mime, File source)
            throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录 " + dir.getAbsolutePath());
        }
        File target = new File(dir, name);
        copy(source, target);
        MediaScannerConnection.scanFile(context.getApplicationContext(),
                new String[]{target.getAbsolutePath()}, new String[]{mime}, null);
        return new Saved(Uri.fromFile(target), target.getAbsolutePath(), target.length());
    }

    // ---------- 图片（图表导出，分享要用 content URI） ----------

    /** 写到 Pictures/PyMoney/文件名，同名覆写，返回相册里的 content URI */
    public static Saved savePicture(Context context, String name, File source) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (mediaStore()) {
            String relative = Environment.DIRECTORY_PICTURES + "/" + SUBDIR + "/";
            List<Uri> rows = queryMediaRows(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    name, relative, context.getPackageName());
            if (!rows.isEmpty()) {
                Uri target = rows.get(0);
                long written = pipe(source, resolver.openOutputStream(target, "wt"));
                deleteStaleRows(resolver, context.getPackageName(), rows, target);
                return new Saved(target, relative + actualName(resolver, target, name), written);
            }
            Uri created = insertPending(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    name, "image/png", relative, true);
            try {
                long written = pipe(source, resolver.openOutputStream(created, "wt"));
                finishPending(resolver, created);
                return new Saved(created, relative + actualName(resolver, created, name), written);
            } catch (IOException io) {
                abandon(resolver, created);
                throw io;
            } catch (RuntimeException rt) {
                abandon(resolver, created);
                throw rt;
            }
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录 " + dir.getAbsolutePath());
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.DATA, new File(dir, name).getAbsolutePath());
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("系统拒绝创建图片");
        }
        long written = pipe(source, resolver.openOutputStream(uri));
        MediaScannerConnection.scanFile(context.getApplicationContext(),
                new String[]{new File(dir, name).getAbsolutePath()},
                new String[]{"image/png"}, null);
        return new Saved(uri, new File(dir, name).getAbsolutePath(), written);
    }

    // ---------- SAF：自选目录 ----------

    /**
     * 直接写进用户授权的那个目录本身，不再自作主张套一层 PyMoney 子目录：
     * 他选「Download/moneyapp」就是想让文件落在那儿，多一层等于让他再去里面找一遍。
     * 同名文件原地覆写，绝不留下带序号的第二份。
     */
    public static Saved saveViaTree(Context context, Uri treeUri, String name, String mime,
            File source) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        // 「树的根」有两种写法：tree/<id>（两段）和 tree/<id>/document/<id>（四段）。
        // 只有四段那种能被 DocumentsContract.getDocumentId() 认出来、也才有 provider 愿意在它下面
        // 建文件；拿两段那种去调 getDocumentId 会抛 IllegalArgumentException: Invalid URI。
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri rootDir = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
        Uri child = findChild(resolver, treeUri, rootId, name);
        String label = treePath(context, treeUri, name);
        if (child == null) {
            Uri created = DocumentsContract.createDocument(resolver, rootDir, mime, name);
            if (created == null) {
                throw new IOException(treeLabel(context, treeUri) + " 不肯创建文件"
                        + "（这个目录或文件管理器可能是只读的）");
            }
            long written = pipe(source, resolver.openOutputStream(created, "wt"));
            return new Saved(created, label, written);
        }
        long written = pipe(source, resolver.openOutputStream(child, "wt"));
        return new Saved(child, label, written);
    }

    /** 单次「另存为」选到的 uri，直接覆写 */
    public static Saved overwrite(Context context, Uri uri, File source) throws IOException {
        long written = pipe(source, context.getContentResolver().openOutputStream(uri, "wt"));
        return new Saved(uri, describeUri(context, uri), written);
    }

    /** 目录树还能写吗：用户在系统设置里撤销授权后，持久授权会从这里消失 */
    public static boolean treeStillGranted(Context context, Uri treeUri) {
        if (treeUri == null) {
            return false;
        }
        String wanted = treeUri.toString();
        List<android.content.UriPermission> permissions
                = context.getContentResolver().getPersistedUriPermissions();
        for (android.content.UriPermission permission : permissions) {
            if (permission.getUri().toString().equals(wanted)) {
                return permission.isWritePermission();
            }
        }
        return false;
    }

    /** 列出父目录的孩子按名字找。selection 有些 provider 不理，所以在客户端比 */
    private static Uri findChild(ContentResolver resolver, Uri treeUri, String parentDocId,
            String name) {
        if (parentDocId == null) {
            return null;
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        Cursor cursor;
        try {
            cursor = resolver.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        } catch (Exception e) {
            return null;
        }
        if (cursor == null) {
            return null;
        }
        try {
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0));
                }
            }
        } finally {
            cursor.close();
        }
        return null;
    }

    // ---------- 把 uri 翻译成人能看懂的位置 ----------

    /** 尽量给出真实可寻址的字符串，仅用于展示，别拿它去 openInputStream */
    public static String describeUri(Context context, Uri uri) {
        if (uri == null) {
            return "未知位置";
        }
        if (isMediaStore(uri)) {
            return mediaPath(context, uri);
        }
        if (isDocument(uri)) {
            return documentPath(context, uri);
        }
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            return path == null ? uri.toString() : path;
        }
        return displayName(context, uri) + "（" + uri.getAuthority() + "）";
    }

    private static boolean isMediaStore(Uri uri) {
        String authority = uri.getAuthority();
        return authority != null && authority.startsWith("media");
    }

    private static boolean isDocument(Uri uri) {
        List<String> segments = uri.getPathSegments();
        return uri.getAuthority() != null && segments != null && segments.contains("document");
    }

    private static String mediaPath(Context context, Uri uri) {
        Cursor cursor;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME, columnPath()},
                    null, null, null);
        } catch (Exception e) {
            return uri.toString();
        }
        if (cursor == null) {
            return uri.toString();
        }
        try {
            if (!cursor.moveToFirst()) {
                return uri.toString();
            }
            String name = cursor.getString(0);
            String where = cursor.getString(1);
            if (mediaStore()) {
                return (where == null ? "" : where) + (name == null ? "" : name);
            }
            return where == null ? name : where;
        } catch (Exception e) {
            return uri.toString();
        } finally {
            cursor.close();
        }
    }

    private static String columnPath() {
        return mediaStore() ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA;
    }

    private static String documentPath(Context context, Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                DocumentsContract.Path result
                        = DocumentsContract.findDocumentPath(context.getContentResolver(), uri);
                List<String> segments = result.getPath();
                if (segments != null && !segments.isEmpty()) {
                    StringBuilder text = new StringBuilder();
                    for (String segment : segments) {
                        if (text.length() > 0) {
                            text.append('/');
                        }
                        text.append(segment);
                    }
                    return text.toString();
                }
            } catch (Exception ignored) {
                // provider 不支持就退回显示名
            }
        }
        return "所选目录/" + displayName(context, uri);
    }

    /** 树的来源标签，用来把备份位置说清楚 */
    public static String treeLabel(Context context, Uri treeUri) {
        String id = DocumentsContract.getTreeDocumentId(treeUri);
        if (id != null && id.startsWith("primary:")) {
            return id.substring("primary:".length());
        }
        if (id == null) {
            return "所选目录";
        }
        Uri root = DocumentsContract.buildTreeDocumentUri(treeUri.getAuthority(), id);
        Cursor cursor;
        try {
            cursor = context.getContentResolver().query(root,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        } catch (Exception e) {
            return id;
        }
        if (cursor == null) {
            return id;
        }
        try {
            if (cursor.moveToFirst() && cursor.getString(0) != null) {
                return cursor.getString(0);
            }
        } finally {
            cursor.close();
        }
        return id;
    }

    private static String treePath(Context context, Uri treeUri, String name) {
        return treeLabel(context, treeUri) + "/" + name;
    }

    // ---------- 小工具 ----------

    /**
     * 找「我们自己在那个子目录里放的那个文件」。
     * 只认 OWNER_PACKAGE_NAME 等于本应用的行：别的 App（或你用文件管理器手动放进去的）同名文件
     * 会被 MediaStore 按同一条 DISPLAY_NAME 查到，我们不能去覆写别人的东西。
     */
    private static List<Uri> queryMediaRows(ContentResolver resolver, Uri collection,
            String name, String relative, String owner) {
        List<Uri> rows = new ArrayList<>();
        Cursor cursor;
        try {
            cursor = resolver.query(collection,
                    new String[]{MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.OWNER_PACKAGE_NAME},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                            + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{name, relative},
                    MediaStore.MediaColumns._ID + " ASC");
        } catch (Exception e) {
            return rows;
        }
        if (cursor == null) {
            return rows;
        }
        try {
            while (cursor.moveToNext()) {
                if (owner == null || owner.equals(cursor.getString(1))) {
                    rows.add(ContentUris.withAppendedId(collection, cursor.getLong(0)));
                }
            }
        } finally {
            cursor.close();
        }
        return rows;
    }

    /**
     * 同名多份是早期「删了重建」留下的历史包袱。
     * 只清理本 App 自己拥有的那些行（OWNER_PACKAGE_NAME 等于自己），别人放的文件一律不碰。
     * 清理失败不影响这次写入的内容（覆写已经完成），只记日志不抛。
     */
    private static void deleteStaleRows(ContentResolver resolver, String owner,
            List<Uri> rows, Uri keep) {
        if (rows.size() < 2) {
            return;
        }
        for (Uri row : rows) {
            if (row.equals(keep)) {
                continue;
            }
            try {
                int removed = resolver.delete(row,
                        MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=?", new String[]{owner});
                if (removed == 0) {
                    Log.i(TAG, "重复的 " + row + " 不是本应用创建的，保留");
                }
            } catch (Exception e) {
                Log.w(TAG, "清理重复行失败 " + row + "：" + e);
            }
        }
    }

    private static Uri insertPending(ContentResolver resolver, Uri collection, String name,
            String mime, String relative, boolean pending) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
        if (pending) {
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }
        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IOException("系统拒绝在 " + relative + " 创建 " + name);
        }
        return uri;
    }

    private static void finishPending(ContentResolver resolver, Uri uri) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        try {
            resolver.update(uri, values, null, null);
        } catch (Exception ignored) {
            // 老 provider 不认 IS_PENDING，写完就算了
        }
    }

    private static void abandon(ContentResolver resolver, Uri uri) {
        try {
            resolver.delete(uri, null, null);
        } catch (Exception ignored) {
            // 删不掉也只是多一个空文件
        }
    }

    /** MediaStore 有权把名字改成 money.db (1)，那就报真实名字，别报我们想要的名字 */
    private static String actualName(ContentResolver resolver, Uri uri, String fallback) {
        Cursor cursor;
        try {
            cursor = resolver.query(uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
        } catch (Exception e) {
            return fallback;
        }
        if (cursor == null) {
            return fallback;
        }
        try {
            if (cursor.moveToFirst() && cursor.getString(0) != null) {
                return cursor.getString(0);
            }
        } finally {
            cursor.close();
        }
        return fallback;
    }

    /**
     * 位置字符串 = 目录 + 落下来的真实文件名。
     * 名字被 MediaStore 加了序号，说明那个目录里已经有一份同名文件而我们「看不见它」：
     * Android 10 以后非本应用创建的行不在我们的查询结果里，但插入时的重名检查照样算它。
     * 卸载重装最容易留下这种行（文件还在、归属被清掉）。这时候备份的内容是新的，
     * 只是名字变了，Termux 那条 cp money.db 拿到的仍是旧的那一份——必须让用户在屏幕上看见这件事，
     * 不能只报一个漂亮路径。
     */
    private static String located(String dir, Uri uri, ContentResolver resolver, String wanted) {
        String actual = actualName(resolver, uri, wanted);
        if (actual.equals(wanted)) {
            return dir + actual;
        }
        return dir + actual + "（⚠ " + wanted + " 已经存在且不是本应用创建的，媒体库把新文件顶成了这个名字；"
                + "备份本身是新的，但 Termux 的 cp 命令要照着新名字改，或者先把旧那份删掉）";
    }

    /**
     * 写完回读。MediaStore 刚建好的行有时要等一下才读得到（尤其带 IS_PENDING 的那条路），
     * 所以按 uri 读不到就按「文件名 + 子目录」重新找一次自己的行再读，最多三轮。
     * 这一步是「备份真的成功了」的唯一证据，不能省。
     */
    public static File readBackAfterWrite(Context context, Saved saved, String name)
            throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            for (Uri candidate : readCandidates(context, saved)) {
                try {
                    return readToCache(context, candidate,
                            "verify-" + System.currentTimeMillis() + "-" + name);
                } catch (Exception e) {
                    last = new IOException(String.valueOf(describe(e)), e);
                }
            }
            try {
                Thread.sleep(300L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("写完读不回来 " + saved.path
                + (last == null ? "" : "：" + String.valueOf(last.getMessage())));
    }

    /** 先试写进去那个 uri；不行再按名字回查自有行（MediaStore 可能把名字改成了 "xxx (1).db"） */
    private static List<Uri> readCandidates(Context context, Saved saved) {
        List<Uri> out = new ArrayList<>();
        if (saved.uri != null) {
            out.add(saved.uri);
        }
        if (saved.relative != null && isMediaStore(saved.uri)) {
            String name = saved.path != null && saved.path.startsWith(saved.relative)
                    ? saved.path.substring(saved.relative.length()) : null;
            if (name != null && name.length() > 0) {
                out.addAll(queryMediaRows(context.getContentResolver(),
                        MediaStore.Files.getContentUri("external"), name, saved.relative,
                        context.getPackageName()));
            }
        }
        return out;
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String join(List<String> parts, String glue) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (text.length() > 0) {
                text.append(glue);
            }
            text.append(part);
        }
        return text.toString();
    }

    /** 把用户选中的文件拷到缓存目录，方便 sqlite 直接打开；写完回读校验也用它 */
    public static File readToCache(Context context, Uri uri, String name) throws IOException {
        File temp = new File(context.getCacheDir(), name);
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("打不开所选文件");
        }
        OutputStream out = new FileOutputStream(temp);
        try {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            close(in);
            close(out);
        }
        return temp;
    }

    public static String displayName(Context context, Uri uri) {
        Cursor cursor = context.getContentResolver().query(uri,
                new String[0], null, null, null);
        try {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String path = uri.getLastPathSegment();
        return path == null ? "未命名" : path;
    }

    private static long pipe(File source, OutputStream out) throws IOException {
        if (out == null) {
            throw new IOException("系统不肯给写句柄");
        }
        InputStream in = new FileInputStream(source);
        try {
            byte[] buffer = new byte[16 * 1024];
            long total = 0L;
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
            }
            out.flush();
            return total;
        } finally {
            close(in);
            close(out);
        }
    }

    public static void copy(File source, File target) throws IOException {
        InputStream in = new FileInputStream(source);
        OutputStream out = new FileOutputStream(target);
        try {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            close(in);
            close(out);
        }
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 关不上就算了
        }
    }
}
