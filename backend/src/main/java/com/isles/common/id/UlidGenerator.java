package com.isles.common.id;

import java.security.SecureRandom;

/**
 * ULID 生成器（无第三方依赖）：48 位毫秒时间戳 + 80 位随机数，Crockford Base32 编码为 26 位字符串。
 *
 * <p>性质：字典序与时间序一致（可作为游标分页、排序键）；同一毫秒内随机部分自增以保证单调递增，
 * 多实例部署时同一毫秒的随机部分仍可能乱序，业务仅依赖"近似时间有序"。
 */
public final class UlidGenerator {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int ULID_LENGTH = 26;
    private static final int RANDOM_LENGTH = 10; // 80 bit
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Object LOCK = new Object();
    private static long lastTimestamp = -1L;
    private static final byte[] lastRandom = new byte[RANDOM_LENGTH];

    private UlidGenerator() {
    }

    /**
     * 生成一个新的 ULID。
     */
    public static String generate() {
        synchronized (LOCK) {
            long timestamp = System.currentTimeMillis();
            if (timestamp > lastTimestamp) {
                lastTimestamp = timestamp;
                RANDOM.nextBytes(lastRandom);
            } else {
                timestamp = lastTimestamp;
                if (!incrementRandom()) {
                    // 同一毫秒内 80 位随机数耗尽（实际不可能发生），顺延到下一毫秒
                    lastTimestamp = timestamp + 1;
                    timestamp = lastTimestamp;
                    RANDOM.nextBytes(lastRandom);
                }
            }
            return encode(timestamp, lastRandom);
        }
    }

    /**
     * 校验是否为合法 ULID 字符串（26 位 Crockford Base32，排除 I/L/O/U 小写也行）。
     */
    public static boolean isValid(String value) {
        if (value == null || value.length() != ULID_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (decode(value.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    static String encode(long timestamp, byte[] randomBytes) {
        char[] chars = new char[ULID_LENGTH];
        // 时间戳 48 bit -> 10 个 Base32 字符（高位补 2 个 0 bit）
        long time = timestamp;
        for (int i = 9; i >= 0; i--) {
            chars[i] = ENCODING[(int) (time & 0x1f)];
            time >>>= 5;
        }
        // 随机数 80 bit -> 16 个 Base32 字符
        int bitBuffer = 0;
        int bits = 0;
        int index = 10;
        for (byte b : randomBytes) {
            bitBuffer = (bitBuffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                chars[index++] = ENCODING[(bitBuffer >>> bits) & 0x1f];
            }
        }
        return new String(chars);
    }

    private static boolean incrementRandom() {
        for (int i = RANDOM_LENGTH - 1; i >= 0; i--) {
            if (++lastRandom[i] != 0) {
                return true;
            }
        }
        return false;
    }

    private static int decode(char c) {
        for (int i = 0; i < ENCODING.length; i++) {
            if (ENCODING[i] == c) {
                return i;
            }
        }
        return -1;
    }
}
