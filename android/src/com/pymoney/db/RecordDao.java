package com.pymoney.db;

import com.pymoney.core.Codec;
import com.pymoney.core.CryptoState;
import com.pymoney.core.PlainCodec;
import com.pymoney.core.Sql;
import com.pymoney.model.Entry;

import java.util.ArrayList;
import java.util.List;

/** 收支记录的增删改查。对应原程序 functions.py 的 InsertData/SearchByTime/SearchByType/DelData。 */
public class RecordDao {

    private static final String SELECT_BASE =
            "SELECT i.ID, i.Time, i.Name, i.Amount, i.TypeID, t.TypeName FROM Income i "
                    + "LEFT JOIN IncomeType t ON t.TypeID = i.TypeID ";

    /** 没接 CryptoState 就是明文态：对拍 harness 走这条，行为和加加密之前完全一样 */
    private static final Codec PLAIN = new PlainCodec();

    private final Sql sql;
    private final CryptoState crypto;

    public RecordDao(Sql sql) {
        this(sql, null);
    }

    public RecordDao(Sql sql, CryptoState crypto) {
        this.sql = sql;
        this.crypto = crypto;
    }

    /** 每次读写现取：解锁/锁定之后不必重建 DAO，也不会拿着作废的密钥继续写 */
    private Codec codec() {
        return crypto == null ? PLAIN : crypto.codec();
    }

    /** 查询条件，空列表/空串表示不加这个限制 */
    public static class Filter {
        public int start;              // YYYYMMDD，0 表示不限
        public int end;                // YYYYMMDD，0 表示不限
        public final List<Integer> typeIds = new ArrayList<>();
        public String keyword;         // 按项目名称模糊匹配
        public boolean incomeOnly;
        public boolean expenseOnly;
        public boolean newestFirst = true;
        public int limit;              // 0 表示不限

        public Filter range(int from, int to) {
            start = from;
            end = to;
            return this;
        }

        public Filter addType(int typeId) {
            if (typeId > 0 && !typeIds.contains(typeId)) {
                typeIds.add(typeId);
            }
            return this;
        }
    }

    private static class Where {
        final StringBuilder text = new StringBuilder();
        final List<Object> args = new ArrayList<>();

        void add(String condition, Object... values) {
            if (text.length() > 0) {
                text.append(" AND ");
            }
            text.append(condition);
            for (Object value : values) {
                args.add(value);
            }
        }
    }

    private Where buildWhere(Filter filter) {
        Where where = new Where();
        if (filter.start > 0) {
            where.add("i.Time >= ?", filter.start);
        }
        if (filter.end > 0) {
            where.add("i.Time <= ?", filter.end);
        }
        if (!filter.typeIds.isEmpty()) {
            StringBuilder marks = new StringBuilder();
            for (int i = 0; i < filter.typeIds.size(); i++) {
                marks.append(i == 0 ? "?" : ",?");
            }
            where.add("i.TypeID IN (" + marks + ")", filter.typeIds.toArray());
        }
        // 加密态下 Name 是 base64 密文，SQL 里 LIKE 中文关键字永远匹配不上，
        // 这个条件改到 search() 里解完密再筛
        if (filter.keyword != null && !filter.keyword.trim().isEmpty() && !codec().encrypted()) {
            where.add("i.Name LIKE ?", "%" + filter.keyword.trim() + "%");
        }
        if (filter.incomeOnly) {
            where.add("i.Amount > 0");
        }
        if (filter.expenseOnly) {
            where.add("i.Amount < 0");
        }
        return where;
    }

    public List<Entry> search(Filter filter) {
        String keyword = filter.keyword == null ? "" : filter.keyword.trim();
        boolean postFilter = !keyword.isEmpty() && codec().encrypted();
        Where where = buildWhere(filter);
        StringBuilder statement = new StringBuilder(SELECT_BASE);
        if (where.text.length() > 0) {
            statement.append("WHERE ").append(where.text).append(' ');
        }
        statement.append("ORDER BY i.Time ").append(filter.newestFirst ? "DESC" : "ASC").append(", i.ID DESC");
        if (filter.limit > 0 && !postFilter) {
            statement.append(" LIMIT ").append(filter.limit);
        }
        List<Entry> result = new ArrayList<>();
        for (Object[] row : sql.select(statement.toString(), where.args.toArray())) {
            Entry entry = toEntry(row);
            if (postFilter && (entry.name == null || !entry.name.contains(keyword))) {
                continue;
            }
            result.add(entry);
            if (postFilter && filter.limit > 0 && result.size() >= filter.limit) {
                break;                       // LIMIT 也搬到内存里，否则筛完就凑不满一页
            }
        }
        return result;
    }

    /** 读出来的 Name/TypeName 一律先过 codec：没加密时原样返回 */
    private Entry toEntry(Object[] row) {
        Codec codec = codec();
        return new Entry(asLong(row[0]), (int) asLong(row[1]),
                codec.open(Codec.INCOME_NAME, asString(row[2])),
                asLong(row[3]), (int) asLong(row[4]),
                codec.open(Codec.TYPE_NAME, asString(row[5])));
    }

    /** 某段月份列表用：按项目名聚合，看有没有重复记录 */
    public Entry findById(long id) {
        List<Object[]> rows = sql.select(SELECT_BASE + "WHERE i.ID = ?", id);
        return rows.isEmpty() ? null : toEntry(rows.get(0));
    }

    public long count() {
        return asLong(sql.select("SELECT COUNT(*) FROM Income").get(0)[0]);
    }

    public int earliestTime() {
        List<Object[]> rows = sql.select("SELECT MIN(Time) FROM Income WHERE Time > 0");
        Object value = rows.isEmpty() ? null : rows.get(0)[0];
        return value == null ? 0 : (int) asLong(value);
    }

    public int latestTime() {
        List<Object[]> rows = sql.select("SELECT MAX(Time) FROM Income");
        Object value = rows.isEmpty() ? null : rows.get(0)[0];
        return value == null ? 0 : (int) asLong(value);
    }

    public long insert(Entry entry) {
        return sql.insert("INSERT INTO Income (Time, Name, Amount, TypeID) VALUES (?,?,?,?)",
                entry.time, codec().seal(Codec.INCOME_NAME, entry.name), entry.amount, entry.typeId);
    }

    public void update(Entry entry) {
        sql.execute("UPDATE Income SET Time=?, Name=?, Amount=?, TypeID=? WHERE ID=?",
                entry.time, codec().seal(Codec.INCOME_NAME, entry.name), entry.amount,
                entry.typeId, entry.id);
    }

    public void delete(long id) {
        sql.execute("DELETE FROM Income WHERE ID = ?", id);
    }

    /** 最近一笔记录用的类型，记一笔时默认选中它 */
    public int lastUsedTypeId() {
        List<Object[]> rows = sql.select(
                "SELECT TypeID FROM Income ORDER BY Time DESC, ID DESC LIMIT 1");
        return rows.isEmpty() ? 0 : (int) asLong(rows.get(0)[0]);
    }

    public long countOfType(int typeId) {        return asLong(sql.select("SELECT COUNT(*) FROM Income WHERE TypeID = ?", typeId).get(0)[0]);
    }

    /** 把某类型下的记录挪到另一个类型 */
    public void moveRecords(int fromTypeId, int toTypeId) {
        sql.execute("UPDATE Income SET TypeID = ? WHERE TypeID = ?", toTypeId, fromTypeId);
    }

    /** 导出全部记录用的有序查询 */
    public List<Entry> exportable() {
        Filter all = new Filter();
        all.newestFirst = false;
        return search(all);
    }

    /** CSV 导入：rows 为 [Time(YYYYMMDD), Name, Amount(分), TypeName]，由 Csv 负责把元换算成分。 */
    public int importRows(List<Object[]> rows, TypeDao types) {
        final int[] inserted = {0};
        sql.transaction(new Runnable() {
            @Override
            public void run() {
                for (Object[] row : rows) {
                    int time = (int) asLong(row[0]);
                    String name = asString(row[1]);
                    long amount = asLong(row[2]);
                    String typeName = asString(row[3]);
                    Integer typeId = types.idByName(typeName);
                    if (typeId == null) {
                        typeId = (int) types.ensureType(typeName);
                    }
                    insert(new Entry(0, time, name, amount, typeId, typeName));
                    inserted[0]++;
                }
            }
        });
        return inserted[0];
    }

    static long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return 0L;
        }
        return (long) Double.parseDouble(text);
    }

    static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
