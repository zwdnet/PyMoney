package com.pymoney.db;

import com.pymoney.core.Codec;
import com.pymoney.core.CryptoState;
import com.pymoney.core.PlainCodec;
import com.pymoney.core.Sql;
import com.pymoney.model.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 项目类型表的操作。对应原 tools.py 的 ShowType/SetType/GetTypeIDbyName/GetTypeNamebyID。 */
public class TypeDao {

    private static final Codec PLAIN = new PlainCodec();

    private final Sql sql;
    private final CryptoState crypto;
    /** 类别名解密后按名字的索引。加密态下 SQL 里比不了中文名，只能靠它；64 条，重建一次几十微秒 */
    private Map<String, Integer> byName;

    public TypeDao(Sql sql) {
        this(sql, null);
    }

    public TypeDao(Sql sql, CryptoState crypto) {
        this.sql = sql;
        this.crypto = crypto;
    }

    private Codec codec() {
        return crypto == null ? PLAIN : crypto.codec();
    }

    private void forgetNames() {
        byName = null;
    }

    /** 全部类型，按 TypeID 排序（和原程序 ShowType 的顺序一致） */
    public List<Type> all() {
        List<Type> result = new ArrayList<>();
        for (Object[] row : sql.select(
                "SELECT t.TypeID, t.TypeName, "
                        + "(SELECT COUNT(*) FROM Income i WHERE i.TypeID = t.TypeID), "
                        + "(SELECT COALESCE(SUM(i.Amount),0) FROM Income i WHERE i.TypeID = t.TypeID) "
                        + "FROM IncomeType t ORDER BY t.TypeID")) {
            Type type = new Type((int) RecordDao.asLong(row[0]),
                    codec().open(Codec.TYPE_NAME, RecordDao.asString(row[1])));
            type.count = RecordDao.asLong(row[2]);
            type.amount = RecordDao.asLong(row[3]);
            result.add(type);
        }
        return result;
    }

    public int maxId() {
        List<Object[]> rows = sql.select("SELECT MAX(TypeID) FROM IncomeType");
        Object value = rows.isEmpty() ? null : rows.get(0)[0];
        return value == null ? 0 : (int) RecordDao.asLong(value);
    }

    public Integer idByName(String name) {
        if (name == null) {
            return null;
        }
        if (!codec().encrypted()) {
            List<Object[]> rows = sql.select(
                    "SELECT TypeID FROM IncomeType WHERE TypeName = ?", name);
            return rows.isEmpty() ? null : (int) RecordDao.asLong(rows.get(0)[0]);
        }
        // 密文比对毫无意义（IV 随机，同名字也不相等），只能解密后按明文找
        if (byName == null) {
            Map<String, Integer> index = new LinkedHashMap<>();
            for (Type type : all()) {
                if (type.name != null && !index.containsKey(type.name)) {
                    index.put(type.name, type.id);
                }
            }
            byName = index;
        }
        return byName.get(name);
    }

    public String nameById(int typeId) {
        List<Object[]> rows = sql.select(
                "SELECT TypeName FROM IncomeType WHERE TypeID = ?", typeId);
        return rows.isEmpty() ? null : codec().open(Codec.TYPE_NAME, RecordDao.asString(rows.get(0)[0]));
    }

    /** 已存在就复用，不存在才插入（和原 InsertData 里 typeid==None 才 SetType 的行为一致） */
    public long ensureType(String name) {
        Integer existing = idByName(name);
        if (existing != null) {
            return existing;
        }
        long id = sql.insert("INSERT INTO IncomeType (TypeID, TypeName) VALUES (NULL, ?)",
                codec().seal(Codec.TYPE_NAME, name));
        forgetNames();
        return id;
    }

    public boolean rename(int typeId, String newName) {
        Integer conflict = idByName(newName);
        if (conflict != null && conflict != typeId) {
            return false;   // 重名会让 GetTypeIDbyName 取到错的类型
        }
        sql.execute("UPDATE IncomeType SET TypeName = ? WHERE TypeID = ?",
                codec().seal(Codec.TYPE_NAME, newName), typeId);
        forgetNames();
        return true;
    }

    public void delete(int typeId) {
        sql.execute("DELETE FROM IncomeType WHERE TypeID = ?", typeId);
        forgetNames();
    }

    /** 删除类型并把它的记录挪到另一个类型；targetTypeId<=0 时记录留作未分类 */
    public void deleteAndMove(int typeId, int targetTypeId) {
        sql.transaction(new Runnable() {
            @Override
            public void run() {
                if (targetTypeId > 0 && targetTypeId != typeId) {
                    sql.execute("UPDATE Income SET TypeID = ? WHERE TypeID = ?",
                            targetTypeId, typeId);
                }
                sql.execute("DELETE FROM IncomeType WHERE TypeID = ?", typeId);
            }
        });
        forgetNames();
    }
}
