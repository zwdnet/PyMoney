package com.pymoney.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「这个库加密了吗、现在解开了没有」的唯一权威。
 *
 * 密钥信封存在库内的 AppCrypto 表里，所以备份文件是自描述的：换机、清除数据、
 * 重装之后把备份导回来，照样能凭口令解开。口令本身以任何形式都不落盘，DEK 只在内存里。
 *
 * 三种状态：
 *   明文库     envelope == null                    codec = PlainCodec
 *   已加密未解锁 有信封、内存里没有 DEK              codec = LockedCodec（读给掩码，写拒绝）
 *   已加密已解锁 内存里有 DEK                        codec = AesGcmCodec
 * 迁移跑到一半被杀进程，库里是混合态，三种状态之间切换都不能让界面崩。
 */
public final class CryptoState {

    public static final String TABLE = "AppCrypto";

    private final Sql sql;

    private KeyEnvelope envelope;
    private Codec codec = new PlainCodec();
    private long cursor = -1;

    private CryptoState(Sql sql) {
        this.sql = sql;
    }

    /** 建库之后立刻调它：读信封、决定用哪个编解码器 */
    public static CryptoState resolve(Sql sql) {
        CryptoState state = new CryptoState(sql);
        state.reload();
        return state;
    }

    /** 导入别的库、或者 AppDb.reload() 之后要重新认一遍 */
    public void reload() {
        Map<String, String> values = readRows();
        envelope = KeyEnvelope.fromValues(values);
        String text = values.get(KeyEnvelope.K_CURSOR);
        cursor = text == null ? -1 : parseLong(text, -1);
        codec = envelope == null ? new PlainCodec() : new LockedCodec();
    }

    // ---------- 状态 ----------

    /** 库里有信封，说明开过加密 */
    public boolean encrypted() {
        return envelope != null;
    }

    /** 明文库也算 unlocked——能正常写就算 */
    public boolean unlocked() {
        return codec.unlocked();
    }

    public Codec codec() {
        return codec;
    }

    public KeyEnvelope envelope() {
        return envelope;
    }

    /** 迁移游标：已经迁到哪个 ID，-1 表示没有 */
    public long cursor() {
        return cursor;
    }

    /** 还差多少条没迁成密文（事项名 + 类别名），迁移进度和状态页都用它 */
    public int[] pending() {
        return new int[]{countPending("Income", "Name"), countPending("IncomeType", "TypeName")};
    }

    public int migrated() {
        return countSealed("Income", "Name") + countSealed("IncomeType", "TypeName");
    }

    private int countPending(String table, String column) {
        return single("SELECT COUNT(*) FROM " + table + " WHERE " + column
                + " IS NOT NULL AND " + column + " <> '' AND " + column + " NOT LIKE 'enc:v1:%'");
    }

    private int countSealed(String table, String column) {
        return single("SELECT COUNT(*) FROM " + table + " WHERE " + column + " LIKE 'enc:v1:%'");
    }

    private int single(String query) {
        List<Object[]> rows = sql.select(query);
        if (rows.isEmpty() || rows.get(0)[0] == null) {
            return 0;
        }
        return (int) toLong(rows.get(0)[0]);
    }

    // ---------- 解锁 / 锁定 ----------

    /** @return 口令对不对；不对时状态不变，也不抛 */
    public boolean unlock(String passphrase) {
        if (envelope == null) {
            return true;
        }
        byte[] key;
        try {
            key = envelope.unlock(passphrase);
        } catch (RuntimeException e) {
            return false;
        }
        return adopt(key);
    }

    /** 恢复码走同一条路，只是 KEK 由恢复码派生 */
    public boolean unlockRecovery(String recoveryCode) {
        if (envelope == null) {
            return true;
        }
        byte[] key;
        try {
            key = envelope.unlockRecovery(recoveryCode);
        } catch (RuntimeException e) {
            return false;
        }
        return adopt(key);
    }

    private boolean adopt(byte[] key) {
        if (key == null) {
            return false;
        }
        applyKey(key);
        return true;
    }

    /** 接手一把新钥：先把上一把在内存里抹干净，再交给编解码器自己留一份。key 为 null 就退回锁住 */
    private void applyKey(byte[] key) {
        releaseCodec();
        if (key == null) {
            codec = envelope == null ? new PlainCodec() : new LockedCodec();
            return;
        }
        codec = new AesGcmCodec(key);
        KeyEnvelope.wipe(key);
    }

    /** 清掉内存里的密钥，回到「看不到明文」的状态 */
    public void lock() {
        releaseCodec();
        codec = envelope == null ? new PlainCodec() : new LockedCodec();
    }

    private void releaseCodec() {
        if (codec instanceof AesGcmCodec) {
            ((AesGcmCodec) codec).destroy();
        }
    }

    // ---------- 改库里的信封 ----------

    /**
     * 开启加密的第一步：把新信封写进库并立刻解锁，之后才轮到 Migrator 搬数据。
     * 先验口令再落库——连自己刚设的口令都解不开，就说明哪儿错了，这时库里一个字节都不该改。
     */
    public boolean install(KeyEnvelope fresh, String passphrase) {
        byte[] key = fresh.unlock(passphrase);
        if (key == null) {
            return false;
        }
        envelope = fresh;
        cursor = -1;                        // 新一轮迁移从头开始，别把上一本的游标带进来
        writeRows(fresh.toValues());
        applyKey(key);
        return true;
    }

    /** 关闭加密（迁回明文）之后：信封没用了，删掉，免得名还挂着「已加密」 */
    public void removeEnvelope() {
        releaseCodec();
        envelope = null;
        cursor = -1;
        sql.execute("DELETE FROM " + TABLE);
        codec = new PlainCodec();
    }

    public void saveCursor(long id) {
        cursor = id;
        sql.execute("INSERT OR REPLACE INTO " + TABLE + "(k, v) VALUES(?, ?)",
                KeyEnvelope.K_CURSOR, String.valueOf(id));
    }

    /** 库里有没有存恢复码包的那一层（只看有没有，恢复码本身任何形式都不落盘） */
    public boolean hasRecoveryCode() {
        return envelope != null && envelope.recoveryWrapped != null;
    }

    /** 改口令只重新包裹 DEK，库里那些密文一个字节都不用动 */
    public boolean changePassphrase(String oldPassphrase, String newPassphrase) {
        if (envelope == null) {
            return false;
        }
        try {
            envelope.changePassphrase(oldPassphrase, newPassphrase);
        } catch (IllegalStateException e) {
            return false;                       // 原口令不对，信封一个字节都没改
        }
        writeRows(envelope.toValues());
        applyKey(envelope.unlock(newPassphrase));
        return unlocked();
    }

    /**
     * 设置/更换恢复码：口令要对，DEK 不变，两次 PBKDF2 大约半秒，别在主线程调。
     * 顺手用这把口令解开，省得用户再输一遍。
     */
    public boolean setRecoveryCode(String passphrase, String recoveryCode) {
        if (envelope == null || recoveryCode == null || recoveryCode.length() == 0) {
            return false;
        }
        byte[] key = envelope.unlock(passphrase);
        if (key == null) {
            return false;
        }
        KeyEnvelope.wipe(key);                    // 先验口令，再改信封
        try {
            envelope.setRecoveryCode(passphrase, recoveryCode);
        } catch (IllegalStateException e) {
            return false;
        }
        writeRows(envelope.toValues());
        applyKey(envelope.unlock(passphrase));
        return unlocked();
    }

    private void writeRows(Map<String, String> values) {
        final Map<String, String> rows = new LinkedHashMap<String, String>(values);
        final long mark = cursor;
        sql.transaction(new Runnable() {
            @Override
            public void run() {
                sql.execute("DELETE FROM " + TABLE);
                for (Map.Entry<String, String> entry : rows.entrySet()) {
                    sql.execute("INSERT OR REPLACE INTO " + TABLE + "(k, v) VALUES(?, ?)",
                            entry.getKey(), entry.getValue());
                }
                // 整张表是先删后插，迁移游标不能被顺手清掉，否则续跑从头再来一遍
                if (mark >= 0) {
                    sql.execute("INSERT OR REPLACE INTO " + TABLE + "(k, v) VALUES(?, ?)",
                            KeyEnvelope.K_CURSOR, String.valueOf(mark));
                }
            }
        });
    }

    private Map<String, String> readRows() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        List<Object[]> rows = sql.select("SELECT k, v FROM " + TABLE);
        for (Object[] row : rows) {
            if (row[0] != null) {
                values.put(String.valueOf(row[0]), row[1] == null ? null : String.valueOf(row[1]));
            }
        }
        return values;
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return parseLong(String.valueOf(value), 0);
    }
}
