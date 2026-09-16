package com.kubo.ui.scope;

import java.util.Locale;

/**
 * Short, uppercase renderings of numbers for the editorial layout.
 */
public final class Format {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};
    // Network full scale: 100 Mbit/s.
    private static final double RATE_FULL_SCALE = 12_500_000;

    private Format() {
    }

    public static String bytes(long bytes) {
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        String number = unit == 0 || value >= 100
                ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.1f", value);
        return number + " " + UNITS[unit];
    }

    static String rate(long bytesPerSecond) {
        return bytes(bytesPerSecond) + "/S";
    }

    static String percent(double value) {
        return String.format(Locale.ROOT, value >= 10 ? "%.0f %%" : "%.1f %%", value);
    }

    static String uptime(long seconds) {
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        if (days > 0) {
            return days + " D " + hours + " H";
        }
        if (hours > 0) {
            return hours + " H " + minutes + " M";
        }
        return minutes + " M";
    }

    /**
     * Throughput on a logarithmic 0..1 scale, so a few kilobytes are already visible.
     */
    static double rateLevel(long bytesPerSecond) {
        return Math.min(1, Math.log10(1 + Math.max(0, bytesPerSecond)) / Math.log10(1 + RATE_FULL_SCALE));
    }
}
