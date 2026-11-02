package com.pymoney.harness;

import com.pymoney.Csv;
import com.pymoney.core.AesGcmCodec;
import com.pymoney.core.Codec;
import com.pymoney.core.CryptoState;
import com.pymoney.core.KeyEnvelope;
import com.pymoney.core.Sql;
import com.pymoney.db.Fingerprint;
import com.pymoney.db.Migrator;
import com.pymoney.db.RecordDao;
import com.pymoney.db.Schema;
import com.pymoney.model.Entry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 迁移的端到端自检，跑在真库 12877 条的副本上。
 *
 * 这里唯一不敢糊弄的是「搬完还得和原来一模一样」：条数、最大ID、时间范围、合计、
 * 类型数逐字段比，再和最初那份明文库逐条比解密结果，最后把库还原成明文再比一次——
 * 可逆性证明的是「加密这个动作本身没动过数据」。
 */
public final class MigrateCheck {

    /** 测试口令。run.sh 从 mig.pass 读同一个串，不两处各写一遍 */
    public static final String PASSPHRASE = "迁移测试口令-PyMoney";
    public static final String RECOVERY = "RECOV-TEST-0001";

    private static int passed;
    private static int failed;

    private MigrateCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: MigrateCheck <明文库> <工作目录>");
            System.exit(2);
        }
        final File plain = new File(args[0]);
        File work = new File(args[1]);
        work.mkdirs();
        Files.write(new File(work, "mig.pass").toPath(),
                PASSPHRASE.getBytes(StandardCharsets.UTF_8));
        // run.sh 要用恢复码验 Termux 那条路，同样从这里读，不在 shell 里再抄一遍常量
        Files.write(new File(work, "mig.rec").toPath(),
                RECOVERY.getBytes(StandardCharsets.UTF_8));

        Sql baseline = new JdbcSql(plain.getPath());
        final Fingerprint before = snapshot(baseline);
        final String baselineCsv = Csv.export(new RecordDao(baseline).exportable());
        final byte[] baselineNames = nameDump(baseline);
        baseline.close();

        checkForward(new File(work, "mig.db"), plain, before, baselineCsv, baselineNames);
        checkInterruptResume(new File(work, "mig2.db"), plain, before, baselineNames);
        checkBackward(new File(work, "mig3.db"), plain, before, baselineCsv, baselineNames);
        checkPlainExport(new File(work, "mig4.db"), plain, before, baselineCsv, baselineNames);

        System.out.println("done|" + passed + "|" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------- 明文 → 密文 ----------

    private static void checkForward(File db, File plain, Fingerprint before,
                                     String baselineCsv, byte[] baselineNames) throws Exception {
        copy(plain, db);
        Sql sql = new JdbcSql(db.getPath());
        try {
            Schema.ensure(sql);
            CryptoState crypto = CryptoState.resolve(sql);
            expect("Migrate|开局是明文", "false", String.valueOf(crypto.encrypted()));

            KeyEnvelope fresh = KeyEnvelope.newEnvelope(PASSPHRASE, RECOVERY);
            expect("Migrate|口令打对才落库", "false",
                    String.valueOf(crypto.install(fresh, "打错的口令")));
            expect("Migrate|失败时库里没留信封", "false", String.valueOf(crypto.encrypted()));

            File plainCopy = new File(db.getParentFile(), "money.db.plain");
            crypto.install(KeyEnvelope.newEnvelope(PASSPHRASE, RECOVERY), PASSPHRASE);
            expect("Migrate|装好信封并解锁", "true|true",
                    crypto.encrypted() + "|" + crypto.unlocked());

            Migrator migrator = new Migrator(sql, crypto);
            Migrator.Result result = migrator.toEncrypted(plainCopy, false, null);
            expect("Migrate|成功|" + result.describe(), "true", String.valueOf(result.ok));
            expect("Migrate|留了明文退路", "true", String.valueOf(plainCopy.exists()));
            Sql copy = new JdbcSql(plainCopy.getPath());
            try {
                expect("Migrate|退路副本可读且指纹一致", "true",
                        String.valueOf(before.sameContent(snapshot(copy))));
            } finally {
                copy.close();
            }

            int[] pending = crypto.pending();
            expect("Migrate|没迁干净的条数", "0,0", pending[0] + "," + pending[1]);
            expect("Migrate|迁完的条数", String.valueOf(before.count + before.typeCount),
                    String.valueOf(crypto.migrated()));
            expect("Migrate|指纹不变|" + snapshot(sql).compact(), "true",
                    String.valueOf(before.sameContent(snapshot(sql))));
            expect("Migrate|逐条解密等于原文", "0",
                    String.valueOf(mismatches(sql, crypto.codec(), baselineNames, Boolean.TRUE)));
            expect("Migrate|CSV 与明文库逐字节相同", "true",
                    String.valueOf(Csv.export(new RecordDao(sql, crypto).exportable())
                            .equals(baselineCsv)));

            // 加密态下关键字检索走「解密后在内存里筛」，SQL 里的 LIKE 比不出中文
            RecordDao records = new RecordDao(sql, crypto);
            RecordDao.Filter filter = new RecordDao.Filter();
            filter.keyword = "早餐";
            filter.limit = 20;
            List<Entry> hits = records.search(filter);
            expect("Migrate|加密态按名字搜", "true",
                    String.valueOf(!hits.isEmpty() && hits.size() <= 20
                            && hits.get(0).name.contains("早餐")));

            crypto.lock();
            expect("Migrate|锁后状态", "true|false",
                    crypto.encrypted() + "|" + crypto.unlocked());
            int masked = 0;
            for (Entry entry : records.search(limited(5))) {
                if (Codec.LOCKED_MASK.equals(entry.name)) {
                    masked++;
                }
            }
            expect("Migrate|锁后全是掩码", "5", String.valueOf(masked));
            boolean refused = false;
            try {
                records.insert(new Entry(0, 20261102, "锁着写一条", -100L, 1, null));
            } catch (IllegalStateException e) {
                refused = true;
            }
            expect("Migrate|锁后禁写", "true", String.valueOf(refused));

            // 恢复码要能顶替口令（忘记口令的唯一出路）
            expect("Migrate|恢复码解锁", "true", String.valueOf(crypto.unlockRecovery(RECOVERY)));
            expect("Migrate|恢复码解开后逐条可读", "0",
                    String.valueOf(mismatches(sql, crypto.codec(), baselineNames, Boolean.TRUE)));
        } finally {
            sql.close();
        }
    }

    // ---------- 跑到第 13 批被打断，接着跑 ----------

    private static void checkInterruptResume(File db, File plain, Fingerprint before,
                                            byte[] baselineNames) throws Exception {
        copy(plain, db);
        Sql sql = new JdbcSql(db.getPath());
        try {
            Schema.ensure(sql);
            final CryptoState crypto = CryptoState.resolve(sql);
            crypto.install(KeyEnvelope.newEnvelope(PASSPHRASE, RECOVERY), PASSPHRASE);
            final Migrator migrator = new Migrator(sql, crypto);
            final int[] batches = {0};
            boolean interrupted = false;
            try {
                migrator.toEncrypted(null, false, new Migrator.Progress() {
                    @Override
                    public void onProgress(int done, int total) {
                        batches[0]++;
                        if (batches[0] >= 13) {
                            throw new IllegalStateException("模拟进程被系统杀掉");
                        }
                    }
                });
            } catch (IllegalStateException e) {
                interrupted = true;
            }
            expect("Migrate|第 13 批处中断", "true", String.valueOf(interrupted));
            long cursor = crypto.cursor();
            expect("Migrate|游标停在第 13 批末尾", "true",
                    String.valueOf(cursor >= 13L * Migrator.BATCH));
            expect("Migrate|打断后是混合态", "true",
                    String.valueOf(crypto.pending()[0] > 0 && crypto.migrated() > 0));
            // 混合态下 App 照样能用：已迁的解得开，没迁的还是明文
            expect("Migrate|混合态逐条可读", "0",
                    String.valueOf(mismatches(sql, crypto.codec(), baselineNames, null)));

            Migrator.Result resumed = migrator.toEncrypted(null, true, null);
            expect("Migrate|续跑成功|" + resumed.describe(), "true", String.valueOf(resumed.ok));
            int[] pending = crypto.pending();
            expect("Migrate|续跑后迁干净了", "0,0", pending[0] + "," + pending[1]);
            expect("Migrate|续跑后游标归零", "0", String.valueOf(crypto.cursor()));
            expect("Migrate|续跑后指纹不变|" + snapshot(sql).compact(), "true",
                    String.valueOf(before.sameContent(snapshot(sql))));
            expect("Migrate|续跑后逐条等于原文", "0",
                    String.valueOf(mismatches(sql, crypto.codec(), baselineNames, Boolean.TRUE)));
        } finally {
            sql.close();
        }
    }

    // ---------- 密文 → 明文 ----------

    private static void checkBackward(File db, File plain, Fingerprint before,
                                      String baselineCsv, byte[] baselineNames) throws Exception {
        // 从 checkForward 留下的那本全密文库出发，看能不能原样退回去
        copy(new File(db.getParentFile(), "mig.db"), db);
        Sql sql = new JdbcSql(db.getPath());
        try {
            CryptoState crypto = CryptoState.resolve(sql);
            expect("Migrate|回退前是密文库", "true", String.valueOf(crypto.encrypted()));
            expect("Migrate|回退前是锁着的", "false", String.valueOf(crypto.unlocked()));
            crypto.unlock(PASSPHRASE);
            String csvEncrypted = Csv.export(new RecordDao(sql, crypto).exportable());

            crypto.lock();
            Migrator blind = new Migrator(sql, crypto);
            Migrator.Result refused = blind.toPlain(null);
            expect("Migrate|锁着不许回退", "false", String.valueOf(refused.ok));
            expect("Migrate|回退被拒的原因|" + refused.error, "true",
                    String.valueOf(refused.error != null && refused.error.indexOf("口令") >= 0));
            crypto.unlock(PASSPHRASE);

            Migrator.Result back = new Migrator(sql, crypto).toPlain(null);
            expect("Migrate|回退成功|" + back.describe(), "true", String.valueOf(back.ok));
            expect("Migrate|回退后没有密文", "0", String.valueOf(crypto.migrated()));
            // 回退之后应当反过来：一条密文都不剩，全都变回明文
            expect("Migrate|回退后全是明文", before.count + "," + before.typeCount,
                    crypto.pending()[0] + "," + crypto.pending()[1]);
            expect("Migrate|回退后信封已删", "false", String.valueOf(crypto.encrypted()));
            expect("Migrate|回退后指纹不变|" + snapshot(sql).compact(), "true",
                    String.valueOf(before.sameContent(snapshot(sql))));
            expect("Migrate|回退后 CSV 与原来逐字节相同", "true",
                    String.valueOf(Csv.export(new RecordDao(sql).exportable()).equals(baselineCsv)));
            expect("Migrate|加密态与明文态 CSV 相同", "true",
                    String.valueOf(csvEncrypted.equals(baselineCsv)));
            expect("Migrate|回退后逐条等于原文", "0",
                    String.valueOf(mismatches(sql, new com.pymoney.core.PlainCodec(),
                            baselineNames, Boolean.FALSE)));
        } finally {
            sql.close();
        }
    }

    // ---------- 导出明文：借当前这把钥就地解一份快照 ----------

    /**
     * 「导出明文 money-plain.db」的实现路径：口令不落盘，所以没法对快照重新解锁，
     * 只能拿正在用的那个 codec 就地解。这里验的是解出来的那份和最初的明文库一模一样，
     * 而且解不开的时候它拒绝写（宁可导不出，也不能导出一堆掩码）。
     */
    private static void checkPlainExport(File db, File plain, Fingerprint before,
                                        String baselineCsv, byte[] baselineNames) throws Exception {
        copy(new File(db.getParentFile(), "mig.db"), db);
        Sql sql = new JdbcSql(db.getPath());
        try {
            CryptoState crypto = CryptoState.resolve(sql);
            expect("Migrate|导明文前是锁着的", "false", String.valueOf(crypto.unlocked()));
            boolean refused = false;
            try {
                Migrator.decryptCopy(sql, crypto.codec());
            } catch (IllegalStateException e) {
                refused = true;                 // 没钥匙就不许假称自己导出了明文
            }
            expect("Migrate|没钥匙拒绝导明文", "true", String.valueOf(refused));

            crypto.unlock(PASSPHRASE);
            int done = Migrator.decryptCopy(sql, crypto.codec());
            expect("Migrate|就地解开的条数", String.valueOf(before.count + before.typeCount),
                    String.valueOf(done));
            expect("Migrate|导出的明文指纹不变|" + snapshot(sql).compact(), "true",
                    String.valueOf(before.sameContent(snapshot(sql))));
            expect("Migrate|导出的明文逐条等于原文", "0",
                    String.valueOf(mismatches(sql, new com.pymoney.core.PlainCodec(),
                            baselineNames, Boolean.FALSE)));
            expect("Migrate|导出的明文 CSV 与原来逐字节相同", "true",
                    String.valueOf(Csv.export(new RecordDao(sql).exportable()).equals(baselineCsv)));
            expect("Migrate|导出的明文没有信封", "false",
                    String.valueOf(CryptoState.resolve(sql).encrypted()));
            expect("Migrate|再解一次没活可干", "0", String.valueOf(Migrator.decryptCopy(sql,
                    crypto.codec())));
        } finally {
            sql.close();
        }
    }

    // ---------- 工具 ----------

    private static RecordDao.Filter limited(int limit) {
        RecordDao.Filter filter = new RecordDao.Filter();
        filter.limit = limit;
        return filter;
    }

    /** 整表「ID + 原文名」一次取平，比一条条查快得多，也免得开一万两千次游标 */
    private static byte[] nameDump(Sql sql) {
        StringBuilder text = new StringBuilder(2 * 1024 * 1024);
        for (Object[] row : sql.select("SELECT ID, Name FROM Income ORDER BY ID")) {
            text.append(asLong(row[0])).append('\t')
                    .append(asString(row[1])).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 解密后逐条和原始明文比，返回对不上的条数。
     * @param expectSealed null=混合态不管（只比内容），TRUE=必须全是密文，FALSE=必须全是明文
     */
    private static int mismatches(Sql sql, Codec codec, byte[] baseline, Boolean expectSealed) {
        String[] lines = new String(baseline, StandardCharsets.UTF_8).split("\n");
        int index = 0;
        int bad = 0;
        for (Object[] row : sql.select("SELECT ID, Name FROM Income ORDER BY ID")) {
            if (index >= lines.length) {
                return bad + 1;
            }
            int tab = lines[index].indexOf('\t');
            String want = lines[index].substring(tab + 1);
            String stored = asString(row[1]);
            String got = codec.open(Codec.INCOME_NAME, stored);
            if (!want.equals(got)) {
                bad++;
            } else if (want.length() == 0 && AesGcmCodec.isSealed(stored)) {
                bad++;                          // 空名不该被加密成一串没意义的密文
            } else if (expectSealed != null && want.length() > 0
                    && expectSealed.booleanValue() != AesGcmCodec.isSealed(stored)) {
                bad++;                          // 该密文的还是明文、该明文的留着密文
            }
            index++;
        }
        return bad + (index == lines.length ? 0 : 1);
    }

    /** 指纹 + 一句「表在不在」，回读副本时用 */
    private static Fingerprint snapshot(Sql sql) {
        if (!Schema.looksLikeMoneyDb(sql)) {
            throw new IllegalStateException("库不像 money.db");
        }
        return Fingerprint.of(sql);
    }

    /** RecordDao 里那两个转换方法是包内的，harness 在别的包里，自己来一份 */
    private static long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static void copy(File from, File to) throws IOException {
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void expect(String label, String want, String got) {
        if (want.equals(got)) {
            passed++;
            System.out.println("ok|" + label + "|" + got);
        } else {
            failed++;
            System.out.println("FAIL|" + label + "| 期望 " + want + " 实得 " + got);
        }
    }
}
