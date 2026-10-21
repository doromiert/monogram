package org.telegram.messenger.utils;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Formatting for existing numeric data; has no store, payment or account integration. */
public final class CurrencyFormatter {
    private static Map<String, Integer> exponents;
    private CurrencyFormatter() {}

    public static synchronized int getCurrencyExp(String currency) {
        if (exponents == null) {
            Map<String, Integer> loaded = new HashMap<>();
            try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("currencies.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int count; (count = in.read(buffer)) != -1;) bytes.write(buffer, 0, count);
                JSONObject data = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                for (Iterator<String> keys = data.keys(); keys.hasNext();) {
                    String key = keys.next();
                    loaded.put(key, data.getJSONObject(key).optInt("exp"));
                }
                exponents = loaded;
            } catch (Exception error) {
                FileLog.e(error);
                return 0;
            }
        }
        Integer exp = exponents.get(currency);
        return exp == null ? 0 : exp;
    }

    public static String formatCurrency(long amount, String currency) {
        return formatCurrency(amount, currency, getCurrencyExp(currency), false);
    }

    public static String formatCurrency(long amount, String currency, int exp) {
        return formatCurrency(amount, currency, exp, false);
    }

    public static String formatCurrency(long amount, String currency, int exp, boolean rounded) {
        if (currency == null || currency.isEmpty()) return String.valueOf(amount);
        try {
            Currency unit = Currency.getInstance(currency);
            NumberFormat format = NumberFormat.getCurrencyInstance();
            format.setCurrency(unit);
            int digits = rounded ? 0 : unit.getDefaultFractionDigits();
            format.setMinimumFractionDigits(Math.max(0, digits));
            format.setMaximumFractionDigits(Math.max(0, digits));
            double value = amount / Math.pow(10, exp);
            return format.format(rounded ? Math.round(value) : value);
        } catch (IllegalArgumentException unknownCurrency) {
            return amount + " " + currency;
        }
    }
}
