package com.kubo.model.files;

import java.util.Optional;

/**
 * The read / write / execute rights of a file for its owner, its group and everyone else, plus the three
 * special bits, as {@code ls -l} prints them ("rwxr-xr-x") and as {@code chmod} takes them ("755").
 *
 * @param bits the mode as a number, 0 to 07777
 */
public record PermissionMode(int bits) {

    public enum Who {
        OWNER(6), GROUP(3), OTHERS(0);

        private final int shift;

        Who(int shift) {
            this.shift = shift;
        }
    }

    public enum Right {
        READ(4, 'r'), WRITE(2, 'w'), EXECUTE(1, 'x');

        private final int value;
        private final char letter;

        Right(int value, char letter) {
            this.value = value;
            this.letter = letter;
        }

        public int value() {
            return value;
        }

        public char letter() {
            return letter;
        }
    }

    public static final int SETUID = 04000;
    public static final int SETGID = 02000;
    public static final int STICKY = 01000;

    public PermissionMode {
        if (bits < 0 || bits > 07777) {
            throw new IllegalArgumentException("mode must be between 0 and 07777");
        }
    }

    /**
     * Reads "drwxr-sr-x", "-rw-r--r--", "drwxrwxrwt." or a bare "rwxr-xr-x"; empty when the text is not a mode.
     */
    public static Optional<PermissionMode> parseSymbolic(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String rights = switch (text.length()) {
            case 9 -> text;
            case 10 -> text.substring(1);
            // SELinux contexts and ACLs add a trailing marker.
            case 11 -> ".+@".indexOf(text.charAt(10)) >= 0 ? text.substring(1, 10) : "";
            default -> "";
        };
        if (rights.length() != 9) {
            return Optional.empty();
        }
        int bits = 0;
        Who[] classes = Who.values();
        int[] specials = {SETUID, SETGID, STICKY};
        for (int i = 0; i < 9; i++) {
            char c = rights.charAt(i);
            Who who = classes[i / 3];
            switch (i % 3) {
                case 0 -> {
                    if (c == 'r') {
                        bits |= Right.READ.value << who.shift;
                    } else if (c != '-') {
                        return Optional.empty();
                    }
                }
                case 1 -> {
                    if (c == 'w') {
                        bits |= Right.WRITE.value << who.shift;
                    } else if (c != '-') {
                        return Optional.empty();
                    }
                }
                default -> {
                    char special = who == Who.OTHERS ? 't' : 's';
                    if (c == 'x' || c == special) {
                        bits |= Right.EXECUTE.value << who.shift;
                    } else if (c != '-' && c != Character.toUpperCase(special)) {
                        return Optional.empty();
                    }
                    if (Character.toLowerCase(c) == special) {
                        bits |= specials[i / 3];
                    }
                }
            }
        }
        return Optional.of(new PermissionMode(bits));
    }

    /**
     * Reads "755", "0644" or "2775"; empty unless the text is three or four octal digits.
     */
    public static Optional<PermissionMode> parseOctal(String text) {
        if (text == null || !text.matches("[0-7]{3,4}")) {
            return Optional.empty();
        }
        return Optional.of(new PermissionMode(Integer.parseInt(text, 8)));
    }

    public boolean has(Who who, Right right) {
        return (bits & (right.value << who.shift)) != 0;
    }

    public PermissionMode with(Who who, Right right, boolean granted) {
        int bit = right.value << who.shift;
        return new PermissionMode(granted ? bits | bit : bits & ~bit);
    }

    /** True when the owner may run it, which is what makes a script something a service can start. */
    public boolean executable() {
        return has(Who.OWNER, Right.EXECUTE);
    }

    /**
     * The same rights, plus the right to run it for everyone who can already read it.
     *
     * <p>0644 becomes 0755 and 0600 becomes 0700: a file everybody can read becomes one everybody can run,
     * and a private one stays private. This is deliberately not what a bare {@code chmod +x} does — that one
     * is filtered by the umask of whoever runs it, so its result cannot be read off the command, and a
     * setting nobody can predict is a poor thing to put behind a button.
     *
     * <p>The special bits are carried through untouched. Kubo never sets setuid, and never clears one
     * somebody else set either.
     */
    public PermissionMode runnable() {
        PermissionMode mode = this;
        for (Who who : Who.values()) {
            if (has(who, Right.READ)) {
                mode = mode.with(who, Right.EXECUTE, true);
            }
        }
        return mode;
    }

    /**
     * True when one of {@link #SETUID}, {@link #SETGID} or {@link #STICKY} is set.
     */
    public boolean hasSpecial(int flag) {
        return (bits & flag) != 0;
    }

    /**
     * 0 to 7: the sum of the rights of one class.
     */
    public int digit(Who who) {
        return (bits >> who.shift) & 7;
    }

    /**
     * Three digits, or four when a special bit is set: "755", "2775".
     */
    public String octal() {
        return (bits & 07000) != 0 ? String.format("%04o", bits) : String.format("%03o", bits);
    }

    /**
     * Always four digits. The leading digit carries the special bits, so they are kept as they are.
     */
    public String chmodArgument() {
        return String.format("%04o", bits);
    }

    /**
     * Nine letters as {@code ls -l} prints them after the type: "rwxr-sr-x".
     */
    public String symbolic() {
        StringBuilder text = new StringBuilder(9);
        for (Who who : Who.values()) {
            text.append(symbolic(digit(who)));
        }
        if (hasSpecial(SETUID)) {
            text.setCharAt(2, has(Who.OWNER, Right.EXECUTE) ? 's' : 'S');
        }
        if (hasSpecial(SETGID)) {
            text.setCharAt(5, has(Who.GROUP, Right.EXECUTE) ? 's' : 'S');
        }
        if (hasSpecial(STICKY)) {
            text.setCharAt(8, has(Who.OTHERS, Right.EXECUTE) ? 't' : 'T');
        }
        return text.toString();
    }

    /**
     * The three letters of one digit: 5 gives "r-x".
     */
    public static String symbolic(int digit) {
        if (digit < 0 || digit > 7) {
            throw new IllegalArgumentException("digit must be between 0 and 7");
        }
        return "" + ((digit & 4) != 0 ? 'r' : '-') + ((digit & 2) != 0 ? 'w' : '-') + ((digit & 1) != 0 ? 'x' : '-');
    }
}
