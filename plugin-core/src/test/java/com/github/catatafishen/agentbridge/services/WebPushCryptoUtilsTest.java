package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WebPushCryptoUtils} — stateless crypto and byte-manipulation
 * utilities used by the Web Push sender.
 */
class WebPushCryptoUtilsTest {

    // ── Helper: generate a P-256 key pair ──────────────────────────────────

    private static KeyPair generateP256KeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    // ── concat ─────────────────────────────────────────────────────────────

    @Test
    void concat_emptyArrays() {
        byte[] result = WebPushCryptoUtils.concat(new byte[0], new byte[0]);
        assertArrayEquals(new byte[0], result);
    }

    @Test
    void concat_singleArray() {
        byte[] input = {1, 2, 3};
        byte[] result = WebPushCryptoUtils.concat(input);
        assertArrayEquals(new byte[]{1, 2, 3}, result);
    }

    @Test
    void concat_multipleArrays() {
        byte[] a = {1, 2};
        byte[] b = {3, 4, 5};
        byte[] c = {6};
        byte[] result = WebPushCryptoUtils.concat(a, b, c);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, result);
    }

    @Test
    void concat_oneEmptyOneNonEmpty() {
        byte[] a = new byte[0];
        byte[] b = {7, 8};
        byte[] result = WebPushCryptoUtils.concat(a, b);
        assertArrayEquals(new byte[]{7, 8}, result);
    }

    @Test
    void concat_noArrays() {
        byte[] result = WebPushCryptoUtils.concat();
        assertArrayEquals(new byte[0], result);
    }

    // ── appendByte ─────────────────────────────────────────────────────────

    @Test
    void appendByte_emptyBase() {
        byte[] result = WebPushCryptoUtils.appendByte(new byte[0], (byte) 0x42);
        assertArrayEquals(new byte[]{0x42}, result);
    }

    @Test
    void appendByte_nonEmptyBase() {
        byte[] result = WebPushCryptoUtils.appendByte(new byte[]{1, 2, 3}, (byte) 4);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, result);
    }

    @Test
    void appendByte_preservesOriginal() {
        byte[] original = {10, 20};
        WebPushCryptoUtils.appendByte(original, (byte) 30);
        // original array must be unmodified
        assertArrayEquals(new byte[]{10, 20}, original);
    }

    // ── toBase64Url ────────────────────────────────────────────────────────

    @Test
    void toBase64Url_replacesPlusSlashEquals() {
        // Standard base64 with all three special characters
        String standard = "abc+/def==";
        String result = WebPushCryptoUtils.toBase64Url(standard);
        assertEquals("abc-_def", result);
    }

    @Test
    void toBase64Url_alreadyUrlSafe() {
        String urlSafe = "abc-_def";
        String result = WebPushCryptoUtils.toBase64Url(urlSafe);
        assertEquals("abc-_def", result);
    }

    @Test
    void toBase64Url_emptyString() {
        assertEquals("", WebPushCryptoUtils.toBase64Url(""));
    }

    @Test
    void toBase64Url_onlyPadding() {
        assertEquals("", WebPushCryptoUtils.toBase64Url("=="));
    }

    @Test
    void toBase64Url_singlePlus() {
        assertEquals("-", WebPushCryptoUtils.toBase64Url("+"));
    }

    @Test
    void toBase64Url_singleSlash() {
        assertEquals("_", WebPushCryptoUtils.toBase64Url("/"));
    }

    // ── toUnsignedBytes ────────────────────────────────────────────────────

    @Test
    void toUnsignedBytes_zero() {
        byte[] result = WebPushCryptoUtils.toUnsignedBytes(BigInteger.ZERO, 4);
        assertArrayEquals(new byte[]{0, 0, 0, 0}, result);
        assertEquals(4, result.length);
    }

    @Test
    void toUnsignedBytes_positiveSmall() {
        // BigInteger(1) → [1], pad to 4 bytes → [0,0,0,1]
        byte[] result = WebPushCryptoUtils.toUnsignedBytes(BigInteger.ONE, 4);
        assertArrayEquals(new byte[]{0, 0, 0, 1}, result);
    }

    @Test
    void toUnsignedBytes_exactLength() {
        // 0x01020304 → 4 bytes exactly
        BigInteger val = new BigInteger("01020304", 16);
        byte[] result = WebPushCryptoUtils.toUnsignedBytes(val, 4);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, result);
        assertEquals(4, result.length);
    }

    @Test
    void toUnsignedBytes_needsTrimming() {
        // A 256-bit positive number has a leading 0x00 sign byte when toByteArray is called,
        // producing 33 bytes. We request 32.
        byte[] raw = new byte[32];
        raw[0] = (byte) 0xFF;
        BigInteger big = new BigInteger(1, raw);
        byte[] result = WebPushCryptoUtils.toUnsignedBytes(big, 32);
        assertEquals(32, result.length);
        assertEquals((byte) 0xFF, result[0]);
    }

    @Test
    void toUnsignedBytes_needsPadding() {
        // BigInteger(255) → [0xFF] (1 byte), pad to 8 bytes
        BigInteger val = BigInteger.valueOf(255);
        byte[] result = WebPushCryptoUtils.toUnsignedBytes(val, 8);
        assertEquals(8, result.length);
        assertEquals((byte) 0xFF, result[7]);
        for (int i = 0; i < 7; i++) {
            assertEquals(0, result[i], "byte at index " + i + " should be zero");
        }
    }

    @Test
    void toUnsignedBytes_lengthOne() {
        byte[] result = WebPushCryptoUtils.toUnsignedBytes(BigInteger.valueOf(42), 1);
        assertArrayEquals(new byte[]{42}, result);
    }

    // ── copyUnsignedBig ────────────────────────────────────────────────────

    @Test
    void copyUnsignedBig_exactFit() {
        byte[] src = {0x0A, 0x0B};
        byte[] dst = new byte[4];
        WebPushCryptoUtils.copyUnsignedBig(src, dst, 0, 2);
        assertArrayEquals(new byte[]{0x0A, 0x0B, 0, 0}, dst);
    }

    @Test
    void copyUnsignedBig_withLeadingZeroStripped() {
        // Simulates DER integer with leading 0x00 sign byte, src length > len
        byte[] src = {0x00, (byte) 0xFF, (byte) 0xAB};
        byte[] dst = new byte[4];
        WebPushCryptoUtils.copyUnsignedBig(src, dst, 1, 2);
        // Should strip the leading 0x00, copy [0xFF, 0xAB] at offset 1
        assertEquals((byte) 0xFF, dst[1]);
        assertEquals((byte) 0xAB, dst[2]);
    }

    @Test
    void copyUnsignedBig_withPadding() {
        // src shorter than len, should left-pad with zeros
        byte[] src = {0x05};
        byte[] dst = new byte[4];
        WebPushCryptoUtils.copyUnsignedBig(src, dst, 0, 4);
        assertArrayEquals(new byte[]{0, 0, 0, 0x05}, dst);
    }

    @Test
    void copyUnsignedBig_atOffset() {
        byte[] src = {0x01, 0x02};
        byte[] dst = new byte[6];
        WebPushCryptoUtils.copyUnsignedBig(src, dst, 3, 3);
        // Should pad 1 zero then copy: dst[3]=0, dst[4]=0x01, dst[5]=0x02
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0x01, 0x02}, dst);
    }

    // ── encodePublicKeyUncompressed + decodePublicKey (roundtrip) ──────────

    @Test
    void encodePublicKeyUncompressed_produces65Bytes() throws Exception {
        KeyPair kp = generateP256KeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        byte[] encoded = WebPushCryptoUtils.encodePublicKeyUncompressed(pub);
        assertEquals(65, encoded.length);
        assertEquals(0x04, encoded[0]);
    }

    @Test
    void decodePublicKey_roundtrip() throws Exception {
        KeyPair kp = generateP256KeyPair();
        ECPublicKey original = (ECPublicKey) kp.getPublic();

        byte[] encoded = WebPushCryptoUtils.encodePublicKeyUncompressed(original);
        ECPublicKey decoded = WebPushCryptoUtils.decodePublicKey(encoded);

        assertNotNull(decoded);
        assertEquals(original.getW().getAffineX(), decoded.getW().getAffineX());
        assertEquals(original.getW().getAffineY(), decoded.getW().getAffineY());
    }

    @Test
    void decodePublicKey_multipleRoundtrips() throws Exception {
        // Verify different key pairs all roundtrip correctly
        for (int i = 0; i < 3; i++) {
            KeyPair kp = generateP256KeyPair();
            ECPublicKey original = (ECPublicKey) kp.getPublic();
            byte[] encoded = WebPushCryptoUtils.encodePublicKeyUncompressed(original);
            ECPublicKey decoded = WebPushCryptoUtils.decodePublicKey(encoded);
            assertEquals(original.getW().getAffineX(), decoded.getW().getAffineX());
            assertEquals(original.getW().getAffineY(), decoded.getW().getAffineY());
        }
    }

    @Test
    void decodePublicKey_wrongLength_throws() {
        byte[] tooShort = new byte[64];
        tooShort[0] = 0x04;
        assertThrows(IllegalArgumentException.class, () -> WebPushCryptoUtils.decodePublicKey(tooShort));
    }

    @Test
    void decodePublicKey_wrongPrefix_throws() {
        byte[] wrongPrefix = new byte[65];
        wrongPrefix[0] = 0x03; // compressed format prefix, not uncompressed
        assertThrows(IllegalArgumentException.class, () -> WebPushCryptoUtils.decodePublicKey(wrongPrefix));
    }

    @Test
    void decodePublicKey_tooLong_throws() {
        byte[] tooLong = new byte[66];
        tooLong[0] = 0x04;
        assertThrows(IllegalArgumentException.class, () -> WebPushCryptoUtils.decodePublicKey(tooLong));
    }

    // ── derToRawEcdsa ──────────────────────────────────────────────────────

    @Test
    void derToRawEcdsa_realSignature_correctLength() throws Exception {
        KeyPair kp = generateP256KeyPair();
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(kp.getPrivate());
        sig.update("test data for signing".getBytes());
        byte[] der = sig.sign();

        int componentLen = 32; // P-256 → 32 bytes per component
        byte[] raw = WebPushCryptoUtils.derToRawEcdsa(der, componentLen);

        assertEquals(64, raw.length, "P-256 raw ECDSA signature must be 64 bytes (r||s)");
    }

    @Test
    void derToRawEcdsa_multipleSignatures_allCorrectLength() throws Exception {
        // Sign multiple times; DER lengths vary due to leading zeros
        KeyPair kp = generateP256KeyPair();
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(kp.getPrivate());

        for (int i = 0; i < 10; i++) {
            sig.update(("message " + i).getBytes());
            byte[] der = sig.sign();
            byte[] raw = WebPushCryptoUtils.derToRawEcdsa(der, 32);
            assertEquals(64, raw.length, "Iteration " + i + ": raw sig must be 64 bytes");
        }
    }

    @Test
    void derToRawEcdsa_handcraftedDer() {
        // Hand-craft a minimal DER: 0x30 len 0x02 rLen r 0x02 sLen s
        // r = [0x01, 0x02], s = [0x03, 0x04], componentLen = 2
        byte[] der = {
                0x30, 0x08,       // SEQUENCE, length 8
                0x02, 0x02,       // INTEGER, length 2
                0x01, 0x02,       // r = [1, 2]
                0x02, 0x02,       // INTEGER, length 2
                0x03, 0x04        // s = [3, 4]
        };
        byte[] raw = WebPushCryptoUtils.derToRawEcdsa(der, 2);
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04}, raw);
    }

    @Test
    void derToRawEcdsa_handcraftedDerWithLeadingZero() {
        // r has a leading 0x00 sign byte (3 bytes for a 2-byte component)
        byte[] der = {
                0x30, 0x09,       // SEQUENCE, length 9
                0x02, 0x03,       // INTEGER, length 3
                0x00, (byte) 0xFF, (byte) 0xAB, // r = [0x00, 0xFF, 0xAB] → stripped to [0xFF, 0xAB]
                0x02, 0x02,       // INTEGER, length 2
                0x03, 0x04        // s = [3, 4]
        };
        byte[] raw = WebPushCryptoUtils.derToRawEcdsa(der, 2);
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xAB, 0x03, 0x04}, raw);
    }

    @Test
    void derToRawEcdsa_shortComponentPadded() {
        // r is only 1 byte but componentLen is 2, so it should be left-padded
        byte[] der = {
                0x30, 0x06,       // SEQUENCE, length 6
                0x02, 0x01,       // INTEGER, length 1
                0x05,             // r = [5]
                0x02, 0x01,       // INTEGER, length 1
                0x06              // s = [6]
        };
        byte[] raw = WebPushCryptoUtils.derToRawEcdsa(der, 2);
        assertArrayEquals(new byte[]{0x00, 0x05, 0x00, 0x06}, raw);
    }

    @Test
    void derToRawEcdsa_notDer_throws() {
        byte[] garbage = {0x01, 0x02, 0x03};
        assertThrows(IllegalArgumentException.class,
                () -> WebPushCryptoUtils.derToRawEcdsa(garbage, 32));
    }

    @Test
    void derToRawEcdsa_missingRTag_throws() {
        byte[] bad = {
                0x30, 0x04,
                0x05, 0x01, 0x00, // not an INTEGER tag (0x05 instead of 0x02)
                0x02, 0x01, 0x00
        };
        assertThrows(IllegalArgumentException.class,
                () -> WebPushCryptoUtils.derToRawEcdsa(bad, 1));
    }

    @Test
    void derToRawEcdsa_missingSTag_throws() {
        byte[] bad = {
                0x30, 0x06,
                0x02, 0x01, 0x0A,  // valid r
                0x05, 0x01, 0x0B   // invalid s tag (0x05 instead of 0x02)
        };
        assertThrows(IllegalArgumentException.class,
                () -> WebPushCryptoUtils.derToRawEcdsa(bad, 1));
    }

    // ── Constructor is private (utility class) ─────────────────────────────

    @Test
    void constructor_isPrivate() throws Exception {
        var ctor = WebPushCryptoUtils.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers()));
    }

    // ── Edge cases combining encode + concat ───────────────────────────────

    @Test
    void encodeAndConcat_roundtripWithPrefix() throws Exception {
        KeyPair kp = generateP256KeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        byte[] encoded = WebPushCryptoUtils.encodePublicKeyUncompressed(pub);
        byte[] prefix = {0x00, 0x41}; // length prefix used in Web Push
        byte[] combined = WebPushCryptoUtils.concat(prefix, encoded);
        assertEquals(67, combined.length);
        assertEquals(0x00, combined[0]);
        assertEquals(0x41, combined[1]);
        assertEquals(0x04, combined[2]);
    }
}
