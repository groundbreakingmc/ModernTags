package com.github.groundbreakingmc.moderntags.util;

public class NumberUtils {

    public static String healthToStr(double v) {
        final int normalized = (int) Math.ceil(v);
        return switch (normalized) {
            case 0 -> "0";
            case 1 -> "1";
            case 2 -> "2";
            case 3 -> "3";
            case 4 -> "4";
            case 5 -> "5";
            case 6 -> "6";
            case 7 -> "7";
            case 8 -> "8";
            case 9 -> "9";
            case 10 -> "10";
            case 11 -> "11";
            case 12 -> "12";
            case 13 -> "13";
            case 14 -> "14";
            case 15 -> "15";
            case 16 -> "16";
            case 17 -> "17";
            case 18 -> "18";
            case 19 -> "19";
            case 20 -> "20";
            default -> Integer.toString(normalized);
        };
    }

    public static int healthToInt(double v) {
        return (int) Math.ceil(v);
    }
}
