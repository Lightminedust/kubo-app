package com.kubo.ui.network;

import java.util.Arrays;

/**
 * Shares the width of one row of the route among the things in it.
 *
 * <p>Each gets what it needs and what is left is spread evenly. When there is not enough, only the widest are
 * cut, and all of them to the same width, so a short name is never cut to make room for a long one.
 */
final class RowShare {

    private RowShare() {
    }

    /**
     * @param natural how wide each thing is when nothing of it is cut
     * @param span    the width of the row
     * @return the width each thing gets, in the order given
     */
    static double[] share(double[] natural, double span) {
        double sum = 0;
        for (double need : natural) {
            sum += need;
        }
        double[] slots = new double[natural.length];
        if (sum <= span) {
            double extra = natural.length == 0 ? 0 : (span - sum) / natural.length;
            for (int i = 0; i < natural.length; i++) {
                slots[i] = natural[i] + extra;
            }
            return slots;
        }
        double[] sorted = natural.clone();
        Arrays.sort(sorted);
        double cap = Double.MAX_VALUE;
        double used = 0;
        for (int i = 0; i < sorted.length; i++) {
            double even = (span - used) / (sorted.length - i);
            if (sorted[i] > even) {
                cap = even;
                break;
            }
            used += sorted[i];
        }
        for (int i = 0; i < natural.length; i++) {
            slots[i] = Math.min(natural[i], cap);
        }
        return slots;
    }
}
