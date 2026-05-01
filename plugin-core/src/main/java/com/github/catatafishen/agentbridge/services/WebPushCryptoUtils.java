package com.github.catatafishen.agentbridge.services;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.util.Arrays;

/**
 * Pure cryptographic and byte-manipulation utilities extracted from {@link WebPushSender}.
 * Stateless, no IntelliJ dependencies.
 */
final class WebPushCryptoUtils {

    private WebPushCryptoUtils() {
        // utility class
    }

    /**
     * Converts DER-encoded ECDSA signature to raw IEEE P1363 format (r||s).
     */
    static byte[] derToRawEcdsa(byte[] der, int componentLen) {
        // DER: 0x30 len 0x02 rLen r 0x02 sLen s
        int i = 2; // skip 0x30 and sequence length
        if (der[0] != 0x30) throw new IllegalArgumentException("Not a DER ECDSA signature");
        // skip r
        if (der[i++] != 0x02) throw new IllegalArgumentException("Missing r INTEGER tag");
        int rLen = der[i++] & 0xFF;
        byte[] r = Arrays.copyOfRange(der, i, i + rLen);
        i += rLen;
        // skip s
        if (der[i++] != 0x02) throw new IllegalArgumentException("Missing s INTEGER tag");
        int sLen = der[i++] & 0xFF;
        byte[] s = Arrays.copyOfRange(der, i, i + sLen);

        byte[] raw = new byte[componentLen * 2];
        // r may be 33 bytes (leading 0x00 for sign), pad/trim to componentLen
        copyUnsignedBig(r, raw, 0, componentLen);
        copyUnsignedBig(s, raw, componentLen, componentLen);
        return raw;
    }

    static byte[] toUnsignedBytes(BigInteger n, int length) {
        byte[] bytes = n.toByteArray();
        if (bytes.length == length) return bytes;
        if (bytes.length > length) return Arrays.copyOfRange(bytes, bytes.length - length, bytes.length);
        byte[] padded = new byte[length];
        System.arraycopy(bytes, 0, padded, length - bytes.length, bytes.length);
        return padded;
    }

    static void copyUnsignedBig(byte[] src, byte[] dst, int dstOffset, int len) {
        // Strip leading zero byte (sign byte for positive BigInteger DER encoding)
        int srcStart = (src.length > len && src[0] == 0) ? 1 : 0;
        int srcLen = src.length - srcStart;
        int destStart = dstOffset + (len - srcLen);
        System.arraycopy(src, srcStart, dst, destStart, srcLen);
    }

    static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    static byte[] appendByte(byte[] arr, byte b) {
        byte[] result = Arrays.copyOf(arr, arr.length + 1);
        result[arr.length] = b;
        return result;
    }

    /**
     * Normalises a base64 or base64url string (adds padding if needed, converts to url-safe chars).
     * Browser push subscriptions may use either variant.
     */
    static String toBase64Url(String s) {
        return s.replace('+', '-').replace('/', '_').replace("=", "");
    }

    static byte[] encodePublicKeyUncompressed(ECPublicKey key) {
        ECPoint point = key.getW();
        byte[] x = toUnsignedBytes(point.getAffineX(), 32);
        byte[] y = toUnsignedBytes(point.getAffineY(), 32);
        byte[] result = new byte[65];
        result[0] = 0x04;
        System.arraycopy(x, 0, result, 1, 32);
        System.arraycopy(y, 0, result, 33, 32);
        return result;
    }

    static ECPublicKey decodePublicKey(byte[] uncompressed) throws NoSuchAlgorithmException, InvalidParameterSpecException, InvalidKeySpecException {
        // Expect 65 bytes: uncompressed EC point (0x04 marker byte, then 32-byte x coordinate, then 32-byte y coordinate)
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new IllegalArgumentException("Expected 65-byte uncompressed P-256 key");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        KeyFactory kf = KeyFactory.getInstance("EC");
        AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
        ap.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = ap.getParameterSpec(ECParameterSpec.class);
        return (ECPublicKey) kf.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }
}
