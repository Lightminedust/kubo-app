package com.kubo.model.files;

import com.kubo.model.files.PermissionMode.Right;
import com.kubo.model.files.PermissionMode.Who;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionModeTest {

    @ParameterizedTest
    @CsvSource({
            "drwxr-xr-x,  755",
            "-rw-r--r--,  644",
            "-rw-------,  600",
            "rwxrwxrwx,   777",
            "----------,  000",
            "drwxr-sr-x,  2755",
            "-rwsr-xr-x,  4755",
            "drwxrwxrwt,  1777",
            "drwxrwxrwT,  1776",
            "-rwSr--r--,  4644",
            "drwxr-xr-x., 755",
            "-rw-r--r--+, 644",
    })
    void symbolicAndOctalAgree(String symbolic, String octal) {
        PermissionMode mode = PermissionMode.parseSymbolic(symbolic).orElseThrow();

        assertEquals(octal, mode.octal());
        assertEquals(PermissionMode.parseOctal(octal).orElseThrow(), mode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "rwx", "drwxr-xr-xx!", "-rwqr--r--", "-rw-r--r-s", "-rwtr--r--", "xrwxr-xr-x7"})
    void rejectsTextThatIsNotAMode(String text) {
        assertTrue(PermissionMode.parseSymbolic(text).isEmpty());
    }

    @Test
    void rejectsNull() {
        assertTrue(PermissionMode.parseSymbolic(null).isEmpty());
        assertTrue(PermissionMode.parseOctal(null).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "75", "75555", "789", "rwx", "-755", " 755"})
    void rejectsTextThatIsNotOctal(String text) {
        assertTrue(PermissionMode.parseOctal(text).isEmpty());
    }

    @Test
    void readsEachRightOfEachClass() {
        PermissionMode mode = PermissionMode.parseOctal("750").orElseThrow();

        assertTrue(mode.has(Who.OWNER, Right.WRITE));
        assertTrue(mode.has(Who.GROUP, Right.EXECUTE));
        assertFalse(mode.has(Who.GROUP, Right.WRITE));
        assertFalse(mode.has(Who.OTHERS, Right.READ));
        assertEquals(7, mode.digit(Who.OWNER));
        assertEquals(5, mode.digit(Who.GROUP));
        assertEquals(0, mode.digit(Who.OTHERS));
    }

    @Test
    void grantingAndRemovingRightsChangesOnlyThatBit() {
        PermissionMode mode = PermissionMode.parseOctal("644").orElseThrow()
                .with(Who.OWNER, Right.EXECUTE, true)
                .with(Who.OTHERS, Right.READ, false);

        assertEquals("740", mode.octal());
        assertEquals("rwxr-----", mode.symbolic());
        assertEquals(mode, mode.with(Who.GROUP, Right.READ, true));
    }

    @Test
    void specialBitsSurviveChanges() {
        PermissionMode mode = PermissionMode.parseSymbolic("drwxrws---").orElseThrow()
                .with(Who.OTHERS, Right.READ, true);

        assertTrue(mode.hasSpecial(PermissionMode.SETGID));
        assertEquals("rwxrwsr--", mode.symbolic());
        assertEquals("2774", mode.chmodArgument());
    }

    @Test
    void chmodArgumentAlwaysHasFourDigits() {
        assertEquals("0755", PermissionMode.parseOctal("755").orElseThrow().chmodArgument());
        assertEquals("0000", new PermissionMode(0).chmodArgument());
    }

    @ParameterizedTest
    @CsvSource({"0, ---", "1, --x", "4, r--", "5, r-x", "6, rw-", "7, rwx"})
    void digitsSpellTheirLetters(int digit, String letters) {
        assertEquals(letters, PermissionMode.symbolic(digit));
    }

    @ParameterizedTest
    @CsvSource({"644, 755", "600, 700", "640, 750", "604, 705", "444, 555", "000, 000", "755, 755"})
    void makingItRunnableFollowsWhoCanRead(String before, String after) {
        assertEquals(after, PermissionMode.parseOctal(before).orElseThrow().runnable().octal());
    }

    @Test
    void makingItRunnableKeepsTheSpecialBits() {
        // Kubo never sets setuid, and never clears one somebody else set either.
        assertEquals("4755", PermissionMode.parseOctal("4644").orElseThrow().runnable().octal());
        assertEquals("2755", PermissionMode.parseOctal("2644").orElseThrow().runnable().octal());
    }

    @Test
    void executableIsAboutTheOwner() {
        assertFalse(PermissionMode.parseOctal("644").orElseThrow().executable());
        assertTrue(PermissionMode.parseOctal("744").orElseThrow().executable());
        // Runnable by the group and by everyone else, but not by the user who owns it: still not runnable
        // by the account a service would start it as when that account owns the file.
        assertFalse(PermissionMode.parseOctal("655").orElseThrow().executable());
    }

    @Test
    void rejectsImpossibleValues() {
        assertThrows(IllegalArgumentException.class, () -> new PermissionMode(-1));
        assertThrows(IllegalArgumentException.class, () -> new PermissionMode(010000));
        assertThrows(IllegalArgumentException.class, () -> PermissionMode.symbolic(8));
    }
}
