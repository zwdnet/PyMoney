#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Termux 侧读加密账本：把 App 加密过的 money.db 解成 CSV、可读列表，或一份明文库。

为什么要有这个文件：App 是唯一的解密入口这件事本身是个单点。算法是公开的
AES-256-GCM + PBKDF2-HMAC-SHA256，信封（salt / 轮数 / wrappedDEK / verifier）就在库里的
AppCrypto 表，所以只要口令在手，不装 App 也能把账读出来——App 哪天装不上了、
手机换了、或者这个仓库不维护了，十几年的账不至于变砖。

这不是后门：口令仍然要人给，脚本里不留任何密钥，猜不出口令就解不开。

    pkg install python-cryptography            # 只需要这一个依赖
    python3 pymoney_dump.py money.db                   # 解成 CSV 打到标准输出
    python3 pymoney_dump.py money.db -o out.csv        # 或写进文件
    python3 pymoney_dump.py money.db --list            # 人眼看的时间/类别/名称/金额
    python3 pymoney_dump.py money.db --sum             # 只给合计数
    python3 pymoney_dump.py money.db --plain plain.db  # 还原一份明文库，给老程序直接用

口令默认交互式输入（不放命令行参数，否则 `ps` 和别人翻 history 都看得见）；
非交互场合用环境变量 PYMONEY_PASS。以 RECOV- 开头的串自动走恢复码那条路。
"""
import argparse
import base64
import csv
import getpass
import hashlib
import os
import shutil
import sqlite3
import sys
from urllib.parse import quote

PREFIX = "enc:v1:"
ITER_KEY, SALT_KEY, DEK_KEY, VERIFY_KEY, RECOVERY_KEY = (
    "iter", "salt", "dek", "verify", "recovery")
WRAP_AAD = b"pymoney.dek-wrap"
VERIFY_AAD = b"pymoney.verify"
VERIFY_PLAIN = b"pymoney.key-check.v1"
INCOME_NAME = b"Income.Name"
TYPE_NAME = b"IncomeType.TypeName"
IV_LEN = 12
TAG_LEN = 16
SALT_LEN = 16
MASK = "🔒 已加密"
BOM = "﻿"


class DeadEnd(Exception):
    """把「为什么解不开」说清楚，而不是抛一个 base64 的调用栈"""


def b64decode(text):
    try:
        return base64.b64decode(text, validate=True)
    except Exception:
        raise DeadEnd("密文里有一段不是合法的 base64，这个库可能被改动过")


def decrypt(key, packed, aad):
    # 空串的密文正好是 iv(12)+tag(16)，所以门槛是「小于」而不是「小于等于」
    if len(packed) < IV_LEN + TAG_LEN:
        raise DeadEnd("密文长度不对，装不下一个 IV 加一个 GCM tag")
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        raise DeadEnd("缺少 cryptography 库。Termux 里先执行：pkg install python-cryptography")
    try:
        return AESGCM(key).decrypt(packed[:IV_LEN], packed[IV_LEN:], aad)
    except Exception:
        raise DeadEnd("解不开——口令不对，或者这份库不是这个口令对应的密钥加密的")


def pbkdf2(secret, salt, iterations, length=32):
    return hashlib.pbkdf2_hmac("sha256", secret.encode("utf-8"), salt, iterations, length)


def read_envelope(db):
    """返回 None 表示这库本来就是明文（没加密，或者已经解过了）"""
    tables = {row[0] for row in db.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    if "AppCrypto" not in tables:
        return None
    values = dict(db.execute("SELECT k, v FROM AppCrypto").fetchall())
    if not values or values.get(DEK_KEY) is None or values.get(SALT_KEY) is None:
        return None
    return {
        "iterations": int(values[ITER_KEY]),
        "salt": b64decode(values[SALT_KEY]),
        "wrapped": b64decode(values[DEK_KEY]),
        "verify": b64decode(values[VERIFY_KEY]),
        "recovery": b64decode(values[RECOVERY_KEY]) if values.get(RECOVERY_KEY) else None,
    }


def unwrap(envelope, secret):
    """先派生 KEK 取出 DEK，再用 DEK 校验那个固定串；串对上了才说明口令真的对"""
    if secret.startswith("RECOV-"):
        blob = envelope["recovery"]
        if blob is None:
            raise DeadEnd("这份库没有设恢复码，请用登录口令")
        key = pbkdf2(secret, blob[:SALT_LEN], envelope["iterations"])
        packed = blob[SALT_LEN:]
    else:
        key = pbkdf2(secret, envelope["salt"], envelope["iterations"])
        packed = envelope["wrapped"]
    dek = decrypt(key, packed, WRAP_AAD)
    if decrypt(dek, envelope["verify"], VERIFY_AAD) != VERIFY_PLAIN:
        raise DeadEnd("口令能取出密钥但校验串不对，说明信封被改过")
    return dek


def unwrap_cell(dek, stored, aad):
    """混合态是一等公民：没前缀的行本来就还是明文，照常返回"""
    if dek is None or stored is None or not stored.startswith(PREFIX):
        return stored
    plain = decrypt(dek, b64decode(stored[len(PREFIX):]), aad).decode("utf-8")
    if plain == MASK:
        raise DeadEnd("解出来是掩码，库里不该存这种东西")
    return plain


def entries(db, dek):
    """只跑两条 SQL，解密在 Python 里做，避免一万两千次查询"""
    types = {}
    for type_id, stored in db.execute("SELECT TypeID, TypeName FROM IncomeType"):
        types[type_id] = unwrap_cell(dek, stored, TYPE_NAME)
    result = []
    for row_id, time_, stored, amount, type_id in db.execute(
            "SELECT ID, Time, Name, Amount, TypeID FROM Income ORDER BY ID"):
        result.append({"id": row_id, "time": time_, "name": unwrap_cell(dek, stored, INCOME_NAME),
                       "fen": amount, "type": types.get(type_id, "")})
    return result


def yuan(fen):
    fen = int(fen)
    sign = "-" if fen < 0 else ""
    fen = abs(fen)
    return "%s%d.%02d" % (sign, fen // 100, fen % 100)


def write_csv(items, path, bom):
    handle = open(path, "w", newline="", encoding="utf-8") if path else sys.stdout
    try:
        if bom:
            handle.write(BOM)
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(["Time", "Name", "Amount", "TypeName"])
        for item in items:
            writer.writerow([item["time"], item["name"], yuan(item["fen"]), item["type"]])
    finally:
        if path:
            handle.close()


def write_list(items, path):
    handle = open(path, "w", encoding="utf-8") if path else sys.stdout
    try:
        for item in items:
            handle.write("%s  %-10s %10s  %s\n" % (
                item["time"], item["type"], yuan(item["fen"]), item["name"]))
    finally:
        if path:
            handle.close()


def summarize(items):
    income = sum(i["fen"] for i in items if i["fen"] > 0)
    expense = sum(i["fen"] for i in items if i["fen"] < 0)
    times = [i["time"] for i in items if i["time"]]
    print("条数   %d" % len(items))
    print("范围   %s ~ %s" % (min(times or [0]), max(times or [0])))
    print("收入   %s 元" % yuan(income))
    print("支出   %s 元" % yuan(expense))
    print("结余   %s 元" % yuan(income + expense))
    print("类型   %d 个" % len({i["type"] for i in items}))


def make_plain(db_path, out_path, dek):
    """复制一份再就地解开，绝不动用户真正的库；任何一条解不开就不产出半截文件"""
    if dek is None:
        raise DeadEnd("这个库本来就是明文，不需要还原")
    tmp = out_path + ".tmp"
    shutil.copyfile(db_path, tmp)
    db = sqlite3.connect(tmp)
    try:
        for type_id, stored in list(db.execute("SELECT TypeID, TypeName FROM IncomeType")):
            db.execute("UPDATE IncomeType SET TypeName = ? WHERE TypeID = ?",
                       (unwrap_cell(dek, stored, TYPE_NAME), type_id))
        for row_id, stored in list(db.execute("SELECT ID, Name FROM Income")):
            db.execute("UPDATE Income SET Name = ? WHERE ID = ?",
                       (unwrap_cell(dek, stored, INCOME_NAME), row_id))
        # 导出的是明文，留着信封只会让人以为它还是加密的
        db.execute("DELETE FROM AppCrypto")
        left = db.execute("SELECT COUNT(*) FROM Income WHERE Name LIKE 'enc:v1:%'").fetchone()[0]
        if left:
            raise DeadEnd("还有 %d 条没解开，不产出半截明文文件" % left)
        db.commit()
    except Exception:
        db.close()
        if os.path.exists(tmp):
            os.remove(tmp)
        raise
    db.close()
    shutil.move(tmp, out_path)
    print("已写出明文库：%s（口令只用在这一次，文件里没有任何密钥）" % out_path, file=sys.stderr)


def read_secret():
    """口令绝不进命令行参数：argv 在 `ps` 和别人翻 history 时都看得见"""
    secret = os.environ.get("PYMONEY_PASS")
    if secret:
        return secret
    try:
        secret = getpass.getpass("口令（或 RECOV- 开头的恢复码）: ")
    except (EOFError, KeyboardInterrupt):
        raise DeadEnd("这里没法安全地读输入（没有终端）。请用环境变量："
                      "PYMONEY_PASS=口令 python3 pymoney_dump.py …")
    if not secret:
        raise DeadEnd("没输入口令")
    return secret


def main():
    parser = argparse.ArgumentParser(
        description="Termux 侧解开 App 加密的 money.db（CSV / 列表 / 合计 / 明文库）")
    parser.add_argument("db", help="可能是加密的 money.db 路径")
    parser.add_argument("-o", "--out", help="输出文件；不写就到标准输出")
    parser.add_argument("--list", action="store_true", help="输出人眼可读的时间/类别/金额/名称")
    parser.add_argument("--sum", action="store_true", help="只打印条数、范围、收支合计")
    parser.add_argument("--plain", metavar="OUT_DB", help="还原一份明文 money.db 到新文件")
    parser.add_argument("--no-bom", action="store_true", help="CSV 不带 BOM（默认带，Excel 不乱码）")
    args = parser.parse_args()

    if not os.path.exists(args.db):
        print("找不到文件：%s" % args.db, file=sys.stderr)
        return 2

    # 只读打开：这个脚本永远不该写用户给的那本库
    uri = "file:%s?mode=ro" % quote(os.path.abspath(args.db))
    db = sqlite3.connect(uri, uri=True)
    try:
        envelope = read_envelope(db)
        if envelope is None:
            print("提示：这个库没有密钥信封，本来就是明文，直接读。", file=sys.stderr)
            dek = None
        else:
            dek = unwrap(envelope, read_secret())
        items = entries(db, dek)
    except DeadEnd as e:
        print(str(e), file=sys.stderr)
        return 1
    finally:
        db.close()

    if args.sum:
        summarize(items)
    elif args.list:
        write_list(items, args.out)
    elif args.plain:
        try:
            make_plain(args.db, args.plain, dek)
        except DeadEnd as e:
            print(str(e), file=sys.stderr)
            return 1
    else:
        write_csv(items, args.out, bom=not args.no_bom)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BrokenPipeError:                    # 管道给 head/grep 时对方先退出
        os._exit(0)
