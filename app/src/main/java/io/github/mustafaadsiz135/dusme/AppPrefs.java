package io.github.mustafaadsiz135.dusme;

import android.content.Context;
import android.content.SharedPreferences;

/** Kullanici ayarlari: acil arama numarasi ve sureler. */
public final class AppPrefs {
    private static final String FILE = "ayarlar";
    private static final String K_PHONE = "telefon";
    private static final String K_ASK = "soru_gecikmesi_sn";
    private static final String K_ANSWER = "yanit_suresi_sn";

    public static final int DEFAULT_ASK_SEC = 4;
    public static final int DEFAULT_ANSWER_SEC = 7;

    private AppPrefs() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getPhone(Context c) { return sp(c).getString(K_PHONE, ""); }

    public static void setPhone(Context c, String phone) {
        sp(c).edit().putString(K_PHONE, clean(phone)).apply();
    }

    public static int getAskSec(Context c) { return sp(c).getInt(K_ASK, DEFAULT_ASK_SEC); }
    public static int getAnswerSec(Context c) { return sp(c).getInt(K_ANSWER, DEFAULT_ANSWER_SEC); }
    public static long getAskDelayMs(Context c) { return getAskSec(c) * 1000L; }
    public static long getAnswerWindowMs(Context c) { return getAnswerSec(c) * 1000L; }

    public static void setTimes(Context c, int askSec, int answerSec) {
        sp(c).edit()
                .putInt(K_ASK, Math.max(1, Math.min(60, askSec)))
                .putInt(K_ANSWER, Math.max(2, Math.min(60, answerSec)))
                .apply();
    }

    /** Sadece rakamlar ve bastaki + kalir. */
    public static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : raw.trim().toCharArray()) {
            if (Character.isDigit(ch)) sb.append(ch);
            else if (ch == '+' && sb.length() == 0) sb.append(ch);
        }
        return sb.toString();
    }
}
