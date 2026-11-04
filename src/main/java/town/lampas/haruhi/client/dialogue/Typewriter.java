package town.lampas.haruhi.client.dialogue;

/**
 * Reveals a line of dialogue one character at a time, at a fixed speed from the moment it starts. Pure arithmetic on
 * milliseconds and character counts, so the screen can ask how far the text has got on any frame.
 *
 * <p>The total is passed in on each query rather than stored, so a window resize that re-wraps the text keeps the timing.
 */
public final class Typewriter {
    private final double charsPerSecond;
    private final long startMillis;
    private boolean skipped;

    /**
     * @param charsPerSecond how many characters appear per second; zero or less shows everything at once
     * @param startMillis the time the text starts appearing, in the same clock as the queries
     */
    public Typewriter(double charsPerSecond, long startMillis) {
        this.charsPerSecond = charsPerSecond;
        this.startMillis = startMillis;
    }

    /** How many of {@code totalChars} characters are visible at {@code nowMillis}. Never more than the total. */
    public int visibleChars(long nowMillis, int totalChars) {
        if (skipped || charsPerSecond <= 0.0) {
            return totalChars;
        }
        long elapsedMillis = Math.max(0L, nowMillis - startMillis);
        double revealed = Math.floor(elapsedMillis * charsPerSecond / 1000.0);
        return (int) Math.min((double) totalChars, revealed);
    }

    /** True once every character is visible. */
    public boolean isComplete(long nowMillis, int totalChars) {
        return visibleChars(nowMillis, totalChars) >= totalChars;
    }

    /** Shows the whole line from now on, whatever the clock says. */
    public void complete() {
        skipped = true;
    }
}
