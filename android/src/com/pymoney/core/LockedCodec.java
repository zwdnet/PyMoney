package com.pymoney.core;

/**
 * 库已加密但还没输入口令。
 * 读的地方一律给掩码，绝不抛异常（列表页、图表页可能拿着它跑）；
 * 写的地方直接拒绝——把明文写进一个本该是密文的库，是最难发现的那种错误。
 */
public final class LockedCodec implements Codec {

    @Override
    public String seal(String column, String value) {
        throw new IllegalStateException("数据库已加密，请先输入口令解锁");
    }

    @Override
    public String open(String column, String stored) {
        if (stored == null || !AesGcmCodec.isSealed(stored)) {
            return stored;                     // 混合态里还没迁的明文照常显示
        }
        return Codec.LOCKED_MASK;
    }

    @Override
    public boolean encrypted() {
        return true;
    }

    @Override
    public boolean unlocked() {
        return false;
    }
}
