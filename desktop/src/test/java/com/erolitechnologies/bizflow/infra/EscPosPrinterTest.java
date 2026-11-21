package com.erolitechnologies.bizflow.infra;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EscPosPrinterTest {
    @Test
    void buildsInitTextAndCut() {
        byte[] b = EscPosPrinter.build(List.of("TOTAL  $5.00"), false);
        assertEquals(0x1B, b[0] & 0xFF);
        assertEquals('@', b[1]);
        assertTrue(new String(b, java.nio.charset.StandardCharsets.US_ASCII).contains("TOTAL  $5.00\n"));
        assertEquals(0x1D, b[b.length - 4] & 0xFF);   // GS V 66 0 = partial cut at the end
        assertEquals('V', b[b.length - 3]);
    }

    @Test
    void drawerKickOnlyWhenRequested() {
        assertFalse(contains(EscPosPrinter.build(List.of("x"), false), new byte[]{0x1B, 'p'}));
        assertTrue(contains(EscPosPrinter.build(List.of("x"), true), new byte[]{0x1B, 'p'}));
    }

    @Test
    void nonAsciiIsMadeSafe() {
        assertEquals("Cafe EUR?", EscPosPrinter.ascii("Café EUR€").replace("EUR?", "EUR?"));
        assertEquals("Cafe ?", EscPosPrinter.ascii("Café €"));
    }

    @Test
    void sendRejectsMissingOrUnknownPrinter() {
        assertThrows(AppException.class, () -> EscPosPrinter.send("", new byte[0]));
        assertThrows(AppException.class, () -> EscPosPrinter.send("No Such Printer 123", new byte[0]));
    }

    private static boolean contains(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
}
