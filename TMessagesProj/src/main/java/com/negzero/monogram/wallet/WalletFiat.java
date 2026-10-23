// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;

import android.util.JsonReader;
import java.io.*;
import java.net.*;
import java.math.*;
import java.text.*;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Public fiat metadata/rates only. No wallet addresses, keys or messages are sent. */
public final class WalletFiat {
    public static final String PROVIDER="CoinGecko";
    private static final String ROOT="https://api.coingecko.com/api/v3/";
    public static final long MAX_AGE_MILLIS=15*60_000L;
    private static final BigDecimal ATOMIC=new BigDecimal("1000000000000");
    public static final class Rate {
        public final String currency;
        public final BigDecimal perXmr;
        public final long updatedAt;
        public Rate(String currency,BigDecimal perXmr,long updatedAt){this.currency=currency;this.perXmr=perXmr;this.updatedAt=updatedAt;}
        public boolean fresh(long now){return updatedAt>0 && updatedAt<=now+120_000 && now-updatedAt<=MAX_AGE_MILLIS;}
        public BigInteger atomsFor(BigDecimal fiat,long now){
            if(!fresh(now))throw new IllegalStateException("Refresh the exchange rate before entering a fiat payment.");
            if(fiat==null || fiat.signum()<=0)throw new IllegalArgumentException("Enter a positive fiat amount.");
            // Match the source wallet's conversion: never exceed the requested fiat value.
            BigInteger atoms=fiat.multiply(ATOMIC).divide(perXmr,0,RoundingMode.DOWN).toBigIntegerExact();
            if(atoms.signum()<=0)throw new IllegalArgumentException("That amount is below one atomic XMR unit.");
            if(atoms.bitLength()>64)throw new IllegalArgumentException("That amount exceeds the Monero amount range.");
            return atoms;
        }
        public BigDecimal fiatFor(BigInteger atoms){return new BigDecimal(atoms).multiply(perXmr).divide(ATOMIC);}
    }
    public static final class Format {
        public final String code,title,symbol,decimalSeparator,thousandsSeparator;
        public final boolean symbolLeft,spaceBetween;
        public final int exp;
        private final NumberFormat formatter;
        public Format(String code,Locale locale){
            Currency currency=Currency.getInstance(code);this.code=currency.getCurrencyCode();title=currency.getDisplayName(locale);symbol=currency.getSymbol(locale);exp=Math.max(0,currency.getDefaultFractionDigits());
            formatter=NumberFormat.getCurrencyInstance(locale);formatter.setCurrency(currency);formatter.setMinimumFractionDigits(exp);formatter.setMaximumFractionDigits(exp);
            DecimalFormat decimal=(DecimalFormat)formatter;DecimalFormatSymbols symbols=decimal.getDecimalFormatSymbols();decimalSeparator=String.valueOf(symbols.getDecimalSeparator());thousandsSeparator=String.valueOf(symbols.getGroupingSeparator());
            String prefix=decimal.getPositivePrefix(),suffix=decimal.getPositiveSuffix();symbolLeft=!prefix.isEmpty() && prefix.contains(symbol);String adjacent=symbolLeft ? prefix : suffix;
            spaceBetween=adjacent.codePoints().anyMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c));
        }
        public synchronized String format(BigDecimal amount){return formatter.format(amount);}
    }
    private static boolean fiat(String code){
        try{return code.matches("[A-Z]{3}") && Currency.getInstance(code).getDefaultFractionDigits()>=0;}catch(IllegalArgumentException unknown){return false;}
    }
    public static List<String> supported() throws IOException {
        try(JsonReader input=read("simple/supported_vs_currencies")){
            ArrayList<String> currencies=new ArrayList<>();input.beginArray();
            while(input.hasNext()){String code=input.nextString().toUpperCase(Locale.ROOT);if(fiat(code))currencies.add(code);}
            input.endArray();if(currencies.isEmpty())throw new IOException("The price provider returned no supported fiat currencies.");
            Collections.sort(currencies);return Collections.unmodifiableList(currencies);
        }
    }
    public static Map<String,Rate> rates(Collection<String> currencies) throws IOException {
        TreeSet<String> codes=new TreeSet<>();for(String code:currencies){code=code.toUpperCase(Locale.ROOT);if(!fiat(code))throw new IllegalArgumentException("Unsupported fiat currency.");codes.add(code.toLowerCase(Locale.ROOT));}
        if(codes.isEmpty() || codes.size()>100)throw new IllegalArgumentException("Choose between 1 and 100 fiat currencies.");
        String endpoint="simple/price?ids=monero&vs_currencies="+String.join(",",codes)+"&include_last_updated_at=true&precision=full";
        HashMap<String,BigDecimal> values=new HashMap<>();long updated=0;
        try(JsonReader input=read(endpoint)){
            input.beginObject();while(input.hasNext()){
                if(!input.nextName().equals("monero")){input.skipValue();continue;}
                input.beginObject();while(input.hasNext()){
                    String code=input.nextName();
                    if(code.equals("last_updated_at")){updated=input.nextLong()*1000L;continue;}
                    if(!codes.contains(code)){input.skipValue();continue;}
                    // JsonReader preserves the numeric lexeme; do not round via double.
                    BigDecimal price=new BigDecimal(input.nextString());
                    if(price.signum()<=0 || price.precision()>100 || Math.abs(price.scale())>100)throw new IOException("Invalid exchange rate.");
                    values.put(code.toUpperCase(Locale.ROOT),price);
                }input.endObject();
            }input.endObject();
        }catch(NumberFormatException invalid){throw new IOException("Invalid exchange rate.");}
        if(updated<=0)throw new IOException("The price provider omitted the rate timestamp.");
        HashMap<String,Rate> rates=new HashMap<>();for(Map.Entry<String,BigDecimal> value:values.entrySet())rates.put(value.getKey(),new Rate(value.getKey(),value.getValue(),updated));
        return Collections.unmodifiableMap(rates);
    }
    private static JsonReader read(String path) throws IOException {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(ROOT+path).openConnection();
        connection.setConnectTimeout(12_000);connection.setReadTimeout(15_000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","Monogram-XMR/1");
        try{
            int status=connection.getResponseCode();if(status!=200)throw new IOException(status==429 ? "Price provider rate limit reached. Try again shortly." : "Price provider returned HTTP "+status+".");
            if(connection.getContentLengthLong()>256*1024)throw new IOException("Price response is too large.");
            InputStream body=new FilterInputStream(connection.getInputStream()){
                int remaining=256*1024;
                @Override public int read()throws IOException{if(remaining<=0)throw new IOException("Price response is too large.");int value=super.read();if(value>=0)remaining--;return value;}
                @Override public int read(byte[] b,int off,int len)throws IOException{if(remaining<=0)throw new IOException("Price response is too large.");int count=super.read(b,off,Math.min(len,remaining));if(count>0)remaining-=count;return count;}
                @Override public void close()throws IOException{try{super.close();}finally{connection.disconnect();}}
            };
            return new JsonReader(new InputStreamReader(body,java.nio.charset.StandardCharsets.UTF_8));
        }catch(IOException failure){connection.disconnect();throw failure;}
    }
}
