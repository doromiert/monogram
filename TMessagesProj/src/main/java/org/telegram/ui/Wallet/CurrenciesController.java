package org.telegram.ui.Wallet;

import org.telegram.messenger.R;

import android.content.Context;
import android.content.SharedPreferences;
import android.icu.util.ULocale;
import android.os.Build;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import com.negzero.monogram.wallet.WalletFiat;
import java.math.BigDecimal;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;
import org.json.JSONArray;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_wallet;

import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class CurrenciesController {

    private static final ExecutorService network=Executors.newSingleThreadExecutor();
    public final int currentAccount;
    private final GramWallet wallet;
    private final SharedPreferences preferences;
    private volatile Map<String,WalletFiat.Rate> exactRates=Collections.emptyMap();
    private TL_wallet.currencyRates currencyRates;
    private String currencySetting;
    private boolean loading;
    private long lastAttempt;
    private String error="";
    public CurrenciesController(GramWallet wallet){
        this.wallet=wallet;currentAccount=wallet.currentAccount;
        preferences=ApplicationLoader.applicationContext.getSharedPreferences("monogram_wallet_currencies",Context.MODE_PRIVATE);
        currencySetting=preferences.getString("currency","USD");
        try{
            JSONObject cache=new JSONObject(preferences.getString("cache","{}"));
            JSONArray codes=cache.optJSONArray("codes");JSONObject values=cache.optJSONObject("rates");
            if(codes!=null){ArrayList<String> supported=new ArrayList<>();for(int i=0;i<codes.length();i++)supported.add(codes.getString(i));currencyRates=metadata(supported);}
            if(values!=null){HashMap<String,WalletFiat.Rate> saved=new HashMap<>();java.util.Iterator<String> keys=values.keys();while(keys.hasNext()){
                String code=keys.next();JSONObject value=values.getJSONObject(code);BigDecimal price=new BigDecimal(value.getString("price"));
                if(price.signum()>0 && price.precision()<=100)saved.put(code,new WalletFiat.Rate(code,price,value.getLong("updated")));
            }exactRates=Collections.unmodifiableMap(saved);}
            if(currencyRates!=null){ArrayList<String> names=new ArrayList<>();for(TL_wallet.currencyRate rate:currencyRates.rates)names.add(rate.currency);currencyRates=metadata(names);}
        }catch(Exception invalid){currencyRates=null;exactRates=Collections.emptyMap();}
    }
    public String getCurrency(){return currencySetting;}
    public String getCurrencyTitle(){TL_wallet.currencyRate value=getSelectedCurrencyRate();return value==null ? getCurrency() : value.translatedTitle;}
    public void setCurrency(String currency){
        if(currencyRates==null || currencyRates.rates.stream().noneMatch(rate->rate.currency.equals(currency)))return;
        if(currencySetting.equals(currency))return;currencySetting=currency;preferences.edit().putString("currency",currency).apply();wallet.notifyUpdate();
    }
    public WalletFiat.Rate exactRate(){return exactRate(currencySetting);}
    public WalletFiat.Rate exactRate(String code){
        getCurrencies();WalletFiat.Rate rate=exactRates.get(code);return rate!=null && rate.fresh(System.currentTimeMillis()) ? rate : null;
    }
    public BigDecimal xmrUsdRate(){WalletFiat.Rate rate=exactRate("USD");return rate==null ? BigDecimal.ZERO : rate.perXmr;}
    public BigDecimal currencyPerXmr(){WalletFiat.Rate rate=exactRate();return rate==null ? BigDecimal.ZERO : rate.perXmr;}
    public String getError(){return error;}
    public double getCurrencyRate(){
        WalletFiat.Rate selected=exactRate(),usd=exactRate("USD");
        return selected==null || usd==null ? 0 : selected.perXmr.divide(usd.perXmr,18,java.math.RoundingMode.HALF_EVEN).doubleValue();
    }
    public TL_wallet.currencyRate getSelectedCurrencyRate(){
        TL_wallet.currencyRates rates=getCurrencies();if(rates!=null)for(TL_wallet.currencyRate rate:rates.rates)if(rate.currency.equals(currencySetting))return rate;
        return currencySetting.equals("USD") ? createFallbackUsdRate() : null;
    }
    private TL_wallet.currencyRates metadata(java.util.Collection<String> codes){
        TL_wallet.currencyRates rates=new TL_wallet.currencyRates();Locale locale=LocaleController.getInstance().getCurrentLocale();if(locale==null)locale=Locale.getDefault();
        for(String code:codes)try{
            WalletFiat.Format format=new WalletFiat.Format(code,locale);TL_wallet.currencyRate rate=new TL_wallet.currencyRate();
            rate.currency=code;rate.title=Currency.getInstance(code).getDisplayName(Locale.US);rate.translatedTitle=capitalizeFirstLetter(format.title,locale);
            rate.symbol=format.symbol;rate.symbolLeft=format.symbolLeft;rate.spaceBetween=format.spaceBetween;rate.decimalSeparator=format.decimalSeparator;rate.thousandsSeparator=format.thousandsSeparator;rate.exp=format.exp;
            WalletFiat.Rate usd=exactRates.get("USD"),selected=exactRates.get(code);
            rate.rate=usd==null || selected==null ? 0 : selected.perXmr.divide(usd.perXmr,18,java.math.RoundingMode.HALF_EVEN).doubleValue();rates.rates.add(rate);
        }catch(IllegalArgumentException invalid){/* Unsupported local ISO metadata is omitted. */}
        return rates;
    }
    public TL_wallet.currencyRates getCurrencies(){
        long now=android.os.SystemClock.elapsedRealtime();
        if(!loading && (lastAttempt==0 || now-lastAttempt>=60_000)){
            loading=true;lastAttempt=now;
            network.execute(()->{
                java.util.List<String> codes=null;Map<String,WalletFiat.Rate> fetched=null;String failure="";
                try{codes=WalletFiat.supported();fetched=WalletFiat.rates(codes);}catch(Exception problem){failure=problem.getMessage()==null ? "Exchange rates unavailable" : problem.getMessage();}
                final java.util.List<String> supported=codes;final Map<String,WalletFiat.Rate> values=fetched;final String message=failure;
                AndroidUtilities.runOnUIThread(()->{
                    loading=false;error=message;if(values!=null)exactRates=values;if(supported!=null)currencyRates=metadata(supported);
                    if(currencyRates!=null)try{
                        JSONObject cache=new JSONObject(),rates=new JSONObject();JSONArray names=new JSONArray();for(TL_wallet.currencyRate rate:currencyRates.rates)names.put(rate.currency);
                        for(Map.Entry<String,WalletFiat.Rate> entry:exactRates.entrySet())rates.put(entry.getKey(),new JSONObject().put("price",entry.getValue().perXmr.toPlainString()).put("updated",entry.getValue().updatedAt));
                        cache.put("codes",names).put("rates",rates);preferences.edit().putString("cache",cache.toString()).apply();
                    }catch(Exception ignored){}
                    wallet.notifyUpdate();
                });
            });
        }
        return currencyRates;
    }

    public static String capitalizeFirstLetter(String value, Locale locale) {
        if (TextUtils.isEmpty(value)) {
            return value;
        }
        for (int offset = 0; offset < value.length(); ) {
            final int codePoint = value.codePointAt(offset);
            final int nextOffset = offset + Character.charCount(codePoint);
            if (Character.isLetter(codePoint)) {
                return value.substring(0, offset)
                        + value.substring(offset, nextOffset).toUpperCase(locale)
                        + value.substring(nextOffset);
            }
            offset = nextOffset;
        }
        return value;
    }

    public ArrayList<TL_wallet.currencyRate> getHintCurrencies() {
        final ArrayList<TL_wallet.currencyRate> result = new ArrayList<>();
        final TL_wallet.currencyRates rates = getCurrencies();
        TL_wallet.currencyRate usdRate = null, eurRate = null;
        if (rates != null && rates.rates != null) {
            for (TL_wallet.currencyRate rate : rates.rates) {
                if (rate != null && TextUtils.equals(rate.currency, "USD")) {
                    usdRate = rate;
                } else if (rate != null && TextUtils.equals(rate.currency, "EUR")) {
                    eurRate = rate;
                }
            }
        }
        result.add(usdRate == null ? createFallbackUsdRate() : usdRate);
        if (eurRate != null) result.add(eurRate);
        if (rates == null || rates.rates == null) {
            return result;
        }

        final LinkedHashSet<String> currencyCodes = new LinkedHashSet<>();
        currencyCodes.add(getCurrency());
        addKeyboardCurrencyHints(currencyCodes);
        addCurrencyHintForCountry(currencyCodes, MessagesController.getInstance(currentAccount).config.phoneCountryIso2.get());
        addCurrencyHintForLocale(currencyCodes, LocaleController.getInstance().getCurrentLocale());
        addCurrencyHintForLocale(currencyCodes, LocaleController.getInstance().getSystemDefaultLocale());

        for (String currencyCode : currencyCodes) {
            if (TextUtils.equals(currencyCode, "USD") || TextUtils.equals(currencyCode, "EUR")) {
                continue;
            }
            for (TL_wallet.currencyRate rate : rates.rates) {
                if (rate != null && TextUtils.equals(rate.currency, currencyCode)) {
                    result.add(rate);
                    break;
                }
            }
        }
        return result;
    }

    private TL_wallet.currencyRate createFallbackUsdRate() {
        final TL_wallet.currencyRate rate = new TL_wallet.currencyRate();
        rate.currency = "USD";
        rate.rate = 1.0;
        rate.title = LocaleController.getString(R.string.WalletCurrencyUSD);
        rate.symbol = "$";
        rate.thousandsSeparator = ",";
        rate.decimalSeparator = ".";
        rate.symbolLeft = true;
        rate.spaceBetween = false;
        rate.dropZeros = false;
        rate.exp = 2;
        return rate;
    }

    private static void addKeyboardCurrencyHints(LinkedHashSet<String> currencyCodes) {
        try {
            final InputMethodManager inputMethodManager = (InputMethodManager) ApplicationLoader.applicationContext.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager == null) {
                return;
            }
            addCurrencyHintForKeyboardSubtype(currencyCodes, inputMethodManager.getCurrentInputMethodSubtype());
            final List<InputMethodInfo> inputMethods = inputMethodManager.getEnabledInputMethodList();
            for (InputMethodInfo inputMethod : inputMethods) {
                final List<InputMethodSubtype> subtypes = inputMethodManager.getEnabledInputMethodSubtypeList(inputMethod, true);
                for (InputMethodSubtype subtype : subtypes) {
                    addCurrencyHintForKeyboardSubtype(currencyCodes, subtype);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void addCurrencyHintForKeyboardSubtype(LinkedHashSet<String> currencyCodes, InputMethodSubtype subtype) {
        if (subtype == null || !"keyboard".equals(subtype.getMode())) {
            return;
        }
        String languageTag = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            languageTag = subtype.getLanguageTag();
        }
        if (TextUtils.isEmpty(languageTag)) {
            languageTag = subtype.getLocale();
        }
        if (!TextUtils.isEmpty(languageTag)) {
            addCurrencyHintForLocale(currencyCodes, Locale.forLanguageTag(languageTag.replace('_', '-')));
        }
    }

    private static void addCurrencyHintForLocale(LinkedHashSet<String> currencyCodes, Locale locale) {
        if (locale == null) {
            return;
        }
        String country = locale.getCountry();
        if (TextUtils.isEmpty(country) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            country = ULocale.addLikelySubtags(ULocale.forLocale(locale)).getCountry();
        }
        addCurrencyHintForCountry(currencyCodes, country);
    }

    private static void addCurrencyHintForCountry(LinkedHashSet<String> currencyCodes, String country) {
        if (TextUtils.isEmpty(country)) {
            return;
        }
        try {
            final Currency currency = Currency.getInstance(new Locale("", country.toUpperCase(Locale.US)));
            if (currency != null) {
                currencyCodes.add(currency.getCurrencyCode());
            }
        } catch (IllegalArgumentException ignore) {
        }
    }

}
