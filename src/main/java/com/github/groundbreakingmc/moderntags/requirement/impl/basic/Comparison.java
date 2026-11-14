package com.github.groundbreakingmc.moderntags.requirement.impl.basic;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Comparison rules shared by conditions. PlaceholderAPI values always arrive as text, so a
 * value that reads as a number compares as one, and the usual boolean words count as booleans.
 */
final class Comparison {

    private Comparison() {
    }

    /**
     * Whether two values are equal: numerically when both read as numbers, otherwise as text.
     * Null equals only null.
     */
    static boolean equal(@Nullable Object left, @Nullable Object right) {
        if (left == null || right == null) return left == right;
        final double leftNumber = number(left);
        final double rightNumber = number(right);
        if (!Double.isNaN(leftNumber) && !Double.isNaN(rightNumber)) return leftNumber == rightNumber;
        return left.toString().equals(right.toString());
    }

    /**
     * Reads a value as a boolean: a boolean itself, or the text {@code true}, {@code yes},
     * {@code false} or {@code no} in any case. PlaceholderAPI writes booleans as yes and no
     * by default.
     *
     * @throws IllegalStateException when the value is neither
     */
    static boolean truth(@Nullable Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text) {
            switch (text.strip().toLowerCase(Locale.ROOT)) {
                case "true", "yes" -> {
                    return true;
                }
                case "false", "no" -> {
                    return false;
                }
                default -> {
                }
            }
        }
        throw new IllegalStateException("Expected true, false, yes or no but got: " + value);
    }

    /** The value as a number, or NaN when it does not read as one. */
    private static double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (!(value instanceof String text)) return Double.NaN;
        final String stripped = text.strip();
        // Checked first, so text that is not a number costs no exception.
        return isNumber(stripped) ? Double.parseDouble(stripped) : Double.NaN;
    }

    /** An optional sign, digits and at most one decimal point with digits around it. */
    private static boolean isNumber(String text) {
        int index = text.startsWith("-") || text.startsWith("+") ? 1 : 0;
        boolean digits = false;
        boolean point = false;
        for (; index < text.length(); index++) {
            final char next = text.charAt(index);
            if (next >= '0' && next <= '9') {
                digits = true;
            } else if (next == '.' && !point) {
                point = true;
            } else {
                return false;
            }
        }
        return digits;
    }
}
