package com.pymoney.core;

/** 明文态：什么都不做。老库、以及没开加密的库走这个。 */
public final class PlainCodec implements Codec {

    @Override
    public String seal(String column, String value) {
        return value;
    }

    @Override
    public String open(String column, String stored) {
        return stored;
    }

    @Override
    public boolean encrypted() {
        return false;
    }

    @Override
    public boolean unlocked() {
        return true;
    }
}
