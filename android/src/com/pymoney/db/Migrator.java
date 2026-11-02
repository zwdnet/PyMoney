package com.pymoney.db;

import com.pymoney.core.Codec;
import com.pymoney.core.CryptoState;
import com.pymoney.core.Sql;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把库里的文字在「明文」和「密文」之间搬来搬去。
 *
 * 唯一一次「一条 SQL 改一万两千行」的动作就是它，所以三条底线：
 *   1. 动手之前先在私有目录落一份明文副本，出问题能整体退回去；
 *   2. 分批 + 游标，每批一个事务，中途被杀进程接着跑就行，库里最坏是混合态（混合态是一等公民）；
 *   3. 搬完必须自证：条数/最大ID/时间范围/合计/类型数逐字段和动手前相同，
 *      抽样若干条能原样解回原文，一条对不上就算失败。
 * 任何一步不过，都不删副本、不改状态，把原因交回界面。
 */
public final class Migrator {

    /** 每批 500 条：一万两千条分 26 批，每批一个事务，手机上一批几十毫秒 */
    public static final int BATCH = 500;

    /** 收尾抽检这么多条，太少怕的是「只迁了前几批」这种错 */
    public static final int SAMPLE = 500;

    public interface Progress {
        /** done 是本方向已经搬完的条数，total 是动手前估计的总条数 */
        void onProgress(int done, int total);
    }

    public static final class Result {
        public boolean ok;
        public String error;
        public String copyPath;
        public int sealed;                 // 迁成密文的条数（两个表合计）
        public int plain;                  // 迁回明文的条数
        public int checked;                // 抽检条数
        public Fingerprint before;
        public Fingerprint after;

        public String describe() {
            if (ok) {
                return "迁了 " + (sealed + plain) + " 条，抽检 " + checked + " 条都对得上";
            }
            return "失败：" + error;
        }
    }

    private final Sql sql;
    private final CryptoState crypto;

    public Migrator(Sql sql, CryptoState crypto) {
        this.sql = sql;
        this.crypto = crypto;
    }

    // ---------- 明文 → 密文 ----------

    /**
     * @param plainCopy 安全副本落点（私有目录）。为 null 就不落副本，仅给对拍测试用
     * @param resume    true 时接着库里的 migrate_cursor 往下跑
     */
    public Result toEncrypted(File plainCopy, boolean resume, Progress progress) {
        Result result = new Result();
        if (!crypto.encrypted() || !crypto.unlocked()) {
            result.error = "还没有可用的密钥：先设口令并解锁";
            return result;
        }
        Codec codec = crypto.codec();
        if (!codec.encrypted()) {
            result.error = "当前是明文态，不需要迁移";
            return result;
        }
        Fingerprint before = Fingerprint.of(sql);
        int total = countPending();
        int cursor = resume ? (int) Math.max(0, crypto.cursor()) : 0;
        if (!resume) {
            crypto.saveCursor(0);
        }
        if (plainCopy != null) {
            String failure = snapshot(plainCopy);
            if (failure != null) {
                result.error = failure;
                return result;
            }
            result.copyPath = plainCopy.getAbsolutePath();
        }

        // ① 类别名先来：只有几十条，且税负/CPI 全靠它，先把它搬完最险的一步就过去了
        List<Object[]> types = sql.select("SELECT TypeID, TypeName FROM IncomeType "
                + "WHERE TypeName IS NOT NULL AND TypeName <> '' AND TypeName NOT LIKE 'enc:v1:%'");
        for (Object[] row : types) {
            final int typeId = (int) RecordDao.asLong(row[0]);
            final String name = RecordDao.asString(row[1]);
            sql.transaction(new Runnable() {
                @Override
                public void run() {
                    sql.execute("UPDATE IncomeType SET TypeName = ? WHERE TypeID = ?",
                            codec.seal(Codec.TYPE_NAME, name), typeId);
                }
            });
        }

        // ② 事项名分批，批末记游标
        Map<Long, String> sample = sampleNames(cursor, true);
        int done = types.size();
        int lastId = cursor;
        while (true) {
            List<Object[]> rows = sql.select(
                    "SELECT ID, Name FROM Income WHERE ID > ? "
                            + "AND Name IS NOT NULL AND Name <> '' AND Name NOT LIKE 'enc:v1:%' "
                            + "ORDER BY ID LIMIT " + BATCH, lastId);
            if (rows.isEmpty()) {
                break;
            }
            final List<Object[]> batch = rows;
            sql.transaction(new Runnable() {
                @Override
                public void run() {
                    for (Object[] row : batch) {
                        sql.execute("UPDATE Income SET Name = ? WHERE ID = ?",
                                codec.seal(Codec.INCOME_NAME, RecordDao.asString(row[1])),
                                RecordDao.asLong(row[0]));
                    }
                }
            });
            lastId = (int) RecordDao.asLong(rows.get(rows.size() - 1)[0]);
            crypto.saveCursor(lastId);
            done += rows.size();
            if (progress != null) {
                progress.onProgress(done, total);
            }
        }
        crypto.saveCursor(0);

        result.sealed = done;
        result.before = before;
        result.checked = sample.size();
        result.error = verify(before, codec, sample, true);
        result.ok = result.error == null;
        if (!result.ok && progress != null) {
            progress.onProgress(0, total);
        }
        return result;
    }

    // ---------- 密文 → 明文 ----------

    /** 关闭加密。搬干净了才把信封删掉，中途失败库里就是混合态，照样能接着跑 */
    public Result toPlain(Progress progress) {
        Result result = new Result();
        if (!crypto.encrypted()) {
            result.error = "这个库本来就没有加密";
            return result;
        }
        if (!crypto.unlocked()) {
            result.error = "要先输入口令才能把密文解回明文";
            return result;
        }
        Codec codec = crypto.codec();
        Fingerprint before = Fingerprint.of(sql);
        int total = countSealed();

        List<Object[]> types = sql.select("SELECT TypeID, TypeName FROM IncomeType "
                + "WHERE TypeName LIKE 'enc:v1:%'");
        for (Object[] row : types) {
            final int typeId = (int) RecordDao.asLong(row[0]);
            final String stored = RecordDao.asString(row[1]);
            final String name = codec.open(Codec.TYPE_NAME, stored);
            if (Codec.LOCKED_MASK.equals(name)) {
                result.error = "第 " + typeId +  " 类的密文解不开，不敢写成明文";
                return result;
            }
            sql.transaction(new Runnable() {
                @Override
                public void run() {
                    sql.execute("UPDATE IncomeType SET TypeName = ? WHERE TypeID = ?", name, typeId);
                }
            });
        }

        Map<Long, String> sample = sampleNames(0, false);
        for (Map.Entry<Long, String> entry : sample.entrySet()) {
            sample.put(entry.getKey(), codec.open(Codec.INCOME_NAME, entry.getValue()));
        }

        int done = types.size();
        int lastId = 0;
        while (true) {
            List<Object[]> rows = sql.select(
                    "SELECT ID, Name FROM Income WHERE ID > ? AND Name LIKE 'enc:v1:%' "
                            + "ORDER BY ID LIMIT " + BATCH, lastId);
            if (rows.isEmpty()) {
                break;
            }
            final List<Object[]> batch = rows;
            final List<Object[]> opened = new ArrayList<>();
            for (Object[] row : batch) {
                String name = codec.open(Codec.INCOME_NAME, RecordDao.asString(row[1]));
                if (Codec.LOCKED_MASK.equals(name)) {
                    result.error = "ID " + RecordDao.asLong(row[0])
                            + " 的密文解不开，先确认口令，别硬写明文";
                    return result;
                }
                opened.add(new Object[]{row[0], name});
            }
            sql.transaction(new Runnable() {
                @Override
                public void run() {
                    for (Object[] row : opened) {
                        sql.execute("UPDATE Income SET Name = ? WHERE ID = ?", row[1], row[0]);
                    }
                }
            });
            lastId = (int) RecordDao.asLong(rows.get(rows.size() - 1)[0]);
            done += rows.size();
            if (progress != null) {
                progress.onProgress(done, total);
            }
        }

        crypto.removeEnvelope();
        result.plain = done;
        result.before = before;
        result.checked = sample.size();
        result.error = verify(before, crypto.codec(), sample, false);
        result.ok = result.error == null;
        return result;
    }

    /**
     * 把一份快照里的密文全解成明文，用的是当前内存里这把钥——专门给「导出明文 money-plain.db」用。
     *
     * 为什么不复用 toPlain()：口令按设计不落盘，AppDb 的 DAO 里那把 DEK 是唯一的存在形式，
     * 重新 VACUUM 出来的快照换个 CryptoState 打开是解不开的。所以借正在用的这把钥就地解，
     * 活库一个字节都不动。
     * 任何一条解不开就整趟失败：宁可导出一份都拿不到，也不能写一份半截是掩码的「明文」出去。
     *
     * @return 解开的条数（事项名 + 类别名合计）
     */
    public static int decryptCopy(Sql sql, Codec codec) {
        if (!codec.encrypted() || !codec.unlocked()) {
            throw new IllegalStateException("没有可用的密钥，导不出明文：先在 App 里输入口令");
        }
        int done = 0;
        List<Object[]> types = sql.select("SELECT TypeID, TypeName FROM IncomeType "
                + "WHERE TypeName LIKE 'enc:v1:%'");
        for (Object[] row : types) {
            final String name = codec.open(Codec.TYPE_NAME, RecordDao.asString(row[1]));
            if (Codec.LOCKED_MASK.equals(name)) {
                throw new IllegalStateException("第 " + RecordDao.asLong(row[0]) + " 类解不开");
            }
            sql.execute("UPDATE IncomeType SET TypeName = ? WHERE TypeID = ?",
                    name, row[0]);
            done++;
        }
        int lastId = 0;
        while (true) {
            final List<Object[]> rows = sql.select(
                    "SELECT ID, Name FROM Income WHERE ID > ? AND Name LIKE 'enc:v1:%' "
                            + "ORDER BY ID LIMIT " + BATCH, lastId);
            if (rows.isEmpty()) {
                break;
            }
            // 先整批解完再一个事务写回：一条一个事务的话一万两千行要几十秒，用户等不到
            final List<Object[]> opened = new ArrayList<Object[]>();
            for (Object[] row : rows) {
                String name = codec.open(Codec.INCOME_NAME, RecordDao.asString(row[1]));
                if (Codec.LOCKED_MASK.equals(name)) {
                    throw new IllegalStateException("ID " + RecordDao.asLong(row[0]) + " 解不开");
                }
                opened.add(new Object[]{row[0], name});
            }
            sql.transaction(new Runnable() {
                @Override
                public void run() {
                    for (Object[] row : opened) {
                        sql.execute("UPDATE Income SET Name = ? WHERE ID = ?", row[1], row[0]);
                    }
                }
            });
            lastId = (int) RecordDao.asLong(rows.get(rows.size() - 1)[0]);
            done += rows.size();
        }
        // 信封留在导出文件里只会让人以为它还是加密的；导出的就是明文，把它删干净
        try {
            sql.execute("DELETE FROM " + CryptoState.TABLE);
        } catch (Exception ignored) {
            // 老备份里没这张表，没什么可删的
        }
        return done;
    }

    // ---------- 收尾校验 ----------

    /**
     * @param expectSealed true 要求全部是密文，false 要求全部是明文
     * @param sample       动手前抽的那批「ID → 原文」，用来证明搬完还认得回去
     */
    private String verify(Fingerprint before, Codec codec, Map<Long, String> sample,
                          boolean expectSealed) {
        Fingerprint after = Fingerprint.of(sql);
        if (!before.sameContent(after)) {
            return "数据指纹变了（" + before.diff(after) + "）";
        }
        int leftover = expectSealed ? countPending() : countSealed();
        if (leftover != 0) {
            return expectSealed ? "还有 " + leftover + " 条没迁成密文"
                    : "还有 " + leftover + " 条没还原成明文";
        }
        int wrong = 0;
        for (Map.Entry<Long, String> entry : sample.entrySet()) {
            List<Object[]> rows = sql.select("SELECT Name FROM Income WHERE ID = ?",
                    entry.getKey().longValue());
            if (rows.isEmpty()) {
                wrong++;
                continue;
            }
            String stored = RecordDao.asString(rows.get(0)[0]);
            String text = expectSealed ? codec.open(Codec.INCOME_NAME, stored) : stored;
            if (text == null || !text.equals(entry.getValue())
                    || Codec.LOCKED_MASK.equals(text)
                    || (expectSealed && !com.pymoney.core.AesGcmCodec.isSealed(stored))) {
                wrong++;
            }
        }
        if (wrong != 0) {
            return "抽检 " + sample.size() + " 条里有 " + wrong + " 条对不上原文";
        }
        return null;
    }

    /**
     * 均匀抽 SAMPLE 条：按 ID 取模，别只抽前面的（那样第 20 批出错正好查不出来）。
     * 只抽「这一趟要搬的那些行」：续跑时前面半截已经是密文了，
     * 把密文当原文记进样本，收尾比对必然假失败。
     */
    private Map<Long, String> sampleNames(int fromId, boolean plainOnly) {
        Map<Long, String> sample = new LinkedHashMap<>();
        long max = sql.select("SELECT MAX(ID) FROM Income").get(0)[0] == null ? 0
                : RecordDao.asLong(sql.select("SELECT MAX(ID) FROM Income").get(0)[0]);
        long min = fromId + 1;
        if (max < min) {
            return sample;
        }
        long step = Math.max(1, (max - min) / SAMPLE);
        for (Object[] row : sql.select("SELECT ID, Name FROM Income WHERE ID >= ? AND ID % ? = 0 "
                + "AND Name IS NOT NULL AND Name <> '' AND Name "
                + (plainOnly ? "NOT " : "") + "LIKE 'enc:v1:%' ORDER BY ID", min, step)) {
            sample.put(Long.valueOf(RecordDao.asLong(row[0])), RecordDao.asString(row[1]));
            if (sample.size() >= SAMPLE) {
                break;
            }
        }
        return sample;
    }

    private int countPending() {
        int[] pending = crypto.pending();
        return pending[0] + pending[1];
    }

    private int countSealed() {
        return crypto.migrated();
    }

    /** VACUUM INTO 取一致性快照；失败返回原因，调用方据此中止 */
    private String snapshot(File target) {
        try {
            sql.execute("VACUUM INTO ?", canonical(target));
            return null;
        } catch (Exception e) {
            return "明文副本没写成（" + e + "），没有退路就不动数据";
        }
    }

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception e) {
            return file.getAbsolutePath();
        }
    }
}
