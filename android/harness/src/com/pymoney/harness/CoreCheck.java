package com.pymoney.harness;

import com.pymoney.core.BackupLog;
import com.pymoney.core.BackupRecord;
import com.pymoney.core.Sql;
import com.pymoney.db.Fingerprint;
import com.pymoney.db.Schema;

import java.util.List;
import java.util.Locale;

/**
 * 备份校验三件套的自检：指纹、历史记录的序列化与上限。
 * 用法：java com.pymoney.harness.CoreCheck <money.db>
 */
public class CoreCheck {

    private static int failures;

    public static void main(String[] argv) {
        Locale.setDefault(Locale.US);
        if (argv.length < 1) {
            System.err.println("用法: CoreCheck <money.db>");
            System.exit(2);
        }
        Sql sql = new JdbcSql(argv[0]);
        Schema.ensure(sql);

        Fingerprint before = Fingerprint.of(sql);
        expect(before.count > 0, "指纹应该数出记录");
        expect(before.maxId >= before.count, "maxID 不该小于条数（有删除时才成立，这里只保证不是 0）");
        expect(before.minTime > 0 && before.maxTime >= before.minTime, "时间区间要合法");
        expect(before.sameContent(before), "自己跟自己必须一致");
        expect("完全一致".equals(before.diff(before)), "diff 一致时应说「完全一致」");
        expect(before.describe().indexOf("条") > 0, "describe 要能看懂：" + before.describe());
        System.out.println("ok|指纹|" + before.compact());

        long sumBefore = before.sumAmount;
        sql.execute("INSERT INTO Income(Time, Name, Amount, TypeID) VALUES(?,?,?,?)",
                20990101, "指纹自检", -12345L, 1);
        Fingerprint after = Fingerprint.of(sql);
        expect(!after.sameContent(before), "加一条之后指纹必须变");
        expect(after.count == before.count + 1, "条数 +1");
        expect(after.sumAmount == sumBefore - 12345L, "金额合计要跟着变");
        expect(after.maxTime == 20990101, "最晚时间要跟着变");
        String diff = before.diff(after);
        expect(diff.indexOf("条数") >= 0 && diff.indexOf("金额合计") >= 0, "diff 要说清哪里不同：" + diff);
        System.out.println("ok|指纹变化|" + diff);
        sql.execute("DELETE FROM Income WHERE Time=20990101 AND Name=?", "指纹自检");
        expect(Fingerprint.of(sql).sameContent(before), "删掉自检记录后应回到原指纹");
        System.out.println("ok|指纹还原");

        checkLog();
        sql.close();
        System.out.println(failures == 0 ? "备份自检全部通过" : "失败 " + failures + " 项");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void checkLog() {
        String stored = null;
        for (int i = 1; i <= 10; i++) {
            BackupRecord record = new BackupRecord();
            record.at = 1000 + i;
            record.ok = i % 2 == 0;
            record.target = "media";
            record.path = "Download/PyMoney/money.db";
            record.bytes = 1024 * i;
            record.count = 12877;
            record.maxId = 12924;
            record.minTime = 20111018;
            record.maxTime = 20261102;
            record.sumAmount = -123456789L;
            record.sha8 = "abcdef01";
            record.error = record.ok ? "" : "IOException|换行也塞进来试试";
            stored = BackupLog.append(stored, record);
        }
        List<BackupRecord> rows = BackupLog.parse(stored);
        expect(rows.size() == BackupLog.LIMIT, "历史只该留 " + BackupLog.LIMIT + " 条，实得 " + rows.size());
        expect(rows.get(0).at == 1010, "最新的排最前");
        expect(rows.get(0).ok, "第 10 条（at=1010）是偶数，应记为成功");
        expect(!rows.get(1).ok, "第 9 条（at=1009）应记为失败");
        expect(rows.get(1).error.indexOf('|') < 0, "字段里的分隔符要被替换掉：" + rows.get(1).error);
        expect(rows.get(1).error.indexOf('\n') < 0, "字段里的换行要被替换掉");
        expect(BackupLog.lastVerifiedAt(stored) == 1010, "最近一次成功应是最新那条，实得 "
                + BackupLog.lastVerifiedAt(stored));
        expect(BackupLog.hasVerified(stored), "hasVerified 要说有");

        BackupRecord only = new BackupRecord();
        only.at = 7;
        only.ok = false;
        String failed = BackupLog.append(null, only);
        expect(!BackupLog.hasVerified(failed), "全是失败时不该算已验证");
        expect(BackupLog.lastVerifiedAt(failed) == 0L, "没有成功记录时返回 0");

        expect(BackupRecord.decode("garbage") == null, "残缺行要解成 null 而不是抛");
        expect(BackupRecord.decode(null) == null, "null 行要解成 null");
        expect(BackupLog.parse(null).isEmpty(), "null 历史串应解出空列表");
        expect(BackupLog.append(stored, null) == stored, "追加 null 不该改变历史");

        BackupRecord decoded = BackupLog.parse(stored).get(1);
        expect(decoded.bytes == 1024 * 9 && decoded.count == 12877 && decoded.maxId == 12924
                && decoded.sumAmount == -123456789L && "abcdef01".equals(decoded.sha8),
                "数值字段要原样往返：" + decoded.summary());
        expect(decoded.location().indexOf("Download/PyMoney") > 0, "location 要带路径");
        expect(decoded.targetModeLabel().equals("Download/PyMoney"), "media 模式中文名");
        System.out.println("ok|备份历史|" + rows.size() + " 条 / 最近成功 "
                + BackupLog.lastVerifiedAt(stored));
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            failures++;
            System.out.println("FAIL|" + message);
        }
    }
}
