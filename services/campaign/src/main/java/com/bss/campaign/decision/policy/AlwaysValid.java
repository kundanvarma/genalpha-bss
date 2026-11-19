package com.bss.campaign.decision.policy;

/**
 * Evidence that may be read at any moment. A fixed-sample z-test is honest
 * only when it is looked at once; the tuner looks every ten minutes and a
 * marketer looks whenever the page is open, and a 5 % test read fifty times
 * crowns luck far more often than 5 % of the time. This is the mixture
 * sequential probability ratio test for a difference of two conversion rates
 * (Johari, Pekelis, Walsh 2017): the likelihood of the data under "there is a
 * difference, of about {@code effectSd}" against "there is none". The first
 * time those odds reach {@code 1 / alpha}, calling a winner is wrong with
 * probability at most {@code alpha} — however often anyone looked on the way.
 * The same bound, read backwards, gives an interval that stays valid at every
 * look. Pure arithmetic: same numbers in, same answer out.
 */
public final class AlwaysValid {

    /** The chance of ever crowning a difference that is not there. */
    public static final double DEFAULT_ALPHA = 0.05;
    /** How large a real difference is expected to be: ten points of conversion rate. */
    public static final double DEFAULT_EFFECT_SD = 0.10;
    private static final double ODDS_CAP = 1_000_000d;

    private AlwaysValid() {
    }

    /**
     * {@code difference} is first minus second as a fraction; {@code odds} is the
     * likelihood ratio for "a real difference"; {@code low}/{@code high} bound the
     * difference at {@code 1 − alpha} at every look.
     */
    public record Read(double difference, double odds, double low, double high) {

        public boolean excludesZero() {
            return low > 0 || high < 0;
        }
    }

    public static Read compare(long n1, long c1, long n2, long c2, double effectSd, double alpha) {
        if (n1 <= 0 || n2 <= 0) {
            return new Read(0, 1, -1, 1);
        }
        double difference = (double) c1 / n1 - (double) c2 / n2;
        // the variance uses rates pulled a half-conversion toward the middle, so an
        // arm with no conversions (or nothing but conversions) is not read as certain
        double q1 = (c1 + 0.5) / (n1 + 1.0), q2 = (c2 + 0.5) / (n2 + 1.0);
        double v = q1 * (1 - q1) / n1 + q2 * (1 - q2) / n2;
        double t2 = effectSd * effectSd;
        double odds = Math.sqrt(v / (v + t2)) * Math.exp(t2 * difference * difference / (2 * v * (v + t2)));
        double half = Math.sqrt(2 * v * (v + t2) / t2 * Math.log(Math.sqrt((v + t2) / v) / alpha));
        return new Read(difference, Math.min(odds, ODDS_CAP),
                Math.max(-1, difference - half), Math.min(1, difference + half));
    }

    /** Odds in words a marketer reads: "59 to 1", "3.2 to 1", "over a million to 1". */
    public static String oddsWords(double odds) {
        if (odds >= ODDS_CAP) {
            return "over a million to 1";
        }
        if (odds >= 10) {
            return Math.round(odds) + " to 1";
        }
        if (odds >= 1) {
            return Math.round(odds * 10) / 10.0 + " to 1";
        }
        return "1 to " + Math.round(10 / Math.max(odds, 1e-6)) / 10.0;
    }

    /** A fraction as percentage points with one decimal: 0.123 → 12.3. */
    public static double points(double fraction) {
        return Math.round(fraction * 1000) / 10.0;
    }
}
