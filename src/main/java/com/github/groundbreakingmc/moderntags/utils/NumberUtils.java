package com.github.groundbreakingmc.moderntags.utils;

public class NumberUtils {

    public static String doubleToStr(double v) {
        final int normalized = (int) (v * 2.0 + 0.5);
        return switch (normalized) {
            case 0 -> "0";
            case 1 -> "0.5";
            case 2 -> "1";
            case 3 -> "1.5";
            case 4 -> "2";
            case 5 -> "2.5";
            case 6 -> "3";
            case 7 -> "3.5";
            case 8 -> "4";
            case 9 -> "4.5";
            case 10 -> "5";
            case 11 -> "5.5";
            case 12 -> "6";
            case 13 -> "6.5";
            case 14 -> "7";
            case 15 -> "7.5";
            case 16 -> "8";
            case 17 -> "8.5";
            case 18 -> "9";
            case 19 -> "9.5";
            case 20 -> "10";
            case 21 -> "10.5";
            case 22 -> "11";
            case 23 -> "11.5";
            case 24 -> "12";
            case 25 -> "12.5";
            case 26 -> "13";
            case 27 -> "13.5";
            case 28 -> "14";
            case 29 -> "14.5";
            case 30 -> "15";
            case 31 -> "15.5";
            case 32 -> "16";
            case 33 -> "16.5";
            case 34 -> "17";
            case 35 -> "17.5";
            case 36 -> "18";
            case 37 -> "18.5";
            case 38 -> "19";
            case 39 -> "19.5";
            case 40 -> "20";
            default -> normalized % 2 == 0
                    ? String.valueOf((int) (v + 0.5))
                    : (int) v + ".5";
        };
    }
}
