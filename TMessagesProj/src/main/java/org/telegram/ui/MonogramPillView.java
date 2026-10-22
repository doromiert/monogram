// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;
import android.content.*;
import android.view.*;
import android.widget.TextView;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.*;

final class MonogramPillView extends TextView {
    static final String[] KEYS={"weather","cache","proxy","usd"};
    private static volatile java.util.List<String> cachedActive;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private final BaseFragment host;
    private int index;
    private boolean pending;
    private float downX;
    private final Runnable refresh=()->update(false);
    static SharedPreferences preferences(){return ApplicationLoader.applicationContext.getSharedPreferences("appearance-pills",0);}
    static List<String> active(){
        if(cachedActive==null){ArrayList<String> out=new ArrayList<>();for(String key:preferences().getString("active","").split(","))if(Arrays.asList(KEYS).contains(key)&&!out.contains(key))out.add(key);cachedActive=Collections.unmodifiableList(out);}
        return new ArrayList<>(cachedActive);
    }
    static boolean hasActive(){if(cachedActive==null)active();return !cachedActive.isEmpty();}
    static void save(List<String> keys){cachedActive=Collections.unmodifiableList(new ArrayList<>(keys));preferences().edit().putString("active",android.text.TextUtils.join(",",keys)).apply();}
    static String label(String key){return key.equals("cache")?"Storage usage":key.equals("proxy")?"XMPP connection":key.equals("usd")?"USD exchange rate":"Weather";}
    MonogramPillView(BaseFragment host){super(host.getContext());this.host=host;setTextSize(13);setTypeface(AndroidUtilities.bold());setGravity(Gravity.CENTER);setMinHeight(AndroidUtilities.dp(44));setMaxWidth(AndroidUtilities.dp(120));setPadding(AndroidUtilities.dp(8),0,AndroidUtilities.dp(8),0);setSingleLine(true);setEllipsize(android.text.TextUtils.TruncateAt.END);setContentDescription("Home widget");setOnLongClickListener(v->{host.presentFragment(new MonogramPillsActivity(host.getCurrentAccount()));return true;});setOnClickListener(v->{List<String> active=active();if(active.isEmpty())return;String key=active.get(Math.min(index,active.size()-1));if(key.equals("cache"))host.presentFragment(new CacheControlActivity());else if(key.equals("proxy"))host.presentFragment(new MonogramConnectionActivity(host.getCurrentAccount()));else host.presentFragment(new MonogramPillsActivity(host.getCurrentAccount()));});}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();update(false);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(refresh);super.onDetachedFromWindow();}
    @Override public boolean onTouchEvent(android.view.MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN)downX=e.getX();if(e.getActionMasked()==MotionEvent.ACTION_UP&&Math.abs(e.getX()-downX)>AndroidUtilities.dp(24)){List<String> active=active();if(active.isEmpty())return true;int next=index+(e.getX()<downX?1:-1);index=preferences().getBoolean("loop",true)?(next+active.size())%active.size():Math.max(0,Math.min(active.size()-1,next));performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);update(true);return true;}return super.onTouchEvent(e);}
    private void update(boolean animate){
        removeCallbacks(refresh);List<String> active=active();if(active.isEmpty()){setVisibility(GONE);return;}index=Math.min(index,active.size()-1);String key=active.get(index);
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if(key.equals("proxy")){boolean connected=org.telegram.messenger.xmpp.MonogramController.get(host.getCurrentAccount()).state()==org.telegram.tgnet.ConnectionsManager.ConnectionStateConnected;setText(connected?"Connected":"Connecting");}
        else if(key.equals("usd"))setText(preferences().getString("usd-text","USD"));
        else if(key.equals("cache"))setText(preferences().getString("cache-text","Storage"));
        else setText(preferences().getString("weather-text","Weather"));
        setContentDescription(label(key)+", "+getText());
        if(animate){setAlpha(.4f);animate().alpha(1).setDuration(150).start();}
        if(!pending&&(key.equals("cache")||key.equals("weather")||key.equals("usd"))){pending=true;IO.execute(()->{try{if(key.equals("cache")){if(System.currentTimeMillis()-preferences().getLong("cache-time",0)>600000){long size=directorySize(ApplicationLoader.applicationContext.getCacheDir(),0);preferences().edit().putString("cache-text",AndroidUtilities.formatFileSize(size)).putLong("cache-time",System.currentTimeMillis()).apply();}}else if(key.equals("usd"))exchangeRate();else weather();}catch(Exception ignored){}AndroidUtilities.runOnUIThread(()->{pending=false;if(isAttachedToWindow()){String text=preferences().getString(key.equals("cache")?"cache-text":key.equals("usd")?"usd-text":"weather-text",label(key));if(!active().isEmpty()&&active().get(Math.min(index,active().size()-1)).equals(key))setText(text);}});});}
        if(isAttachedToWindow())postDelayed(refresh,300000);
    }
    private static long directorySize(File dir,int depth){if(depth>12)return 0;long size=0;File[] files=dir.listFiles();if(files!=null)for(File file:files){if(file.isDirectory())size+=directorySize(file,depth+1);else size+=file.length();}return size;}
    private static JSONObject json(String address) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {byte[] buffer=new byte[8192];int count,total=0;while((count=in.read(buffer))!=-1){total+=count;if(total>256*1024)throw new IOException();out.write(buffer,0,count);}return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}finally{c.disconnect();}
    }
    static void findCity(String query,java.util.function.Consumer<List<JSONObject>> ready){IO.execute(()->{List<JSONObject> result=new ArrayList<>();try{JSONArray rows=json("https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&name="+URLEncoder.encode(query.trim(),"UTF-8")).optJSONArray("results");if(rows!=null)for(int i=0;i<rows.length();i++)result.add(rows.getJSONObject(i));}catch(Exception ignored){}AndroidUtilities.runOnUIThread(()->ready.accept(result));});}
    static final String[] CURRENCIES={"EUR","PLN","GBP","CHF","CZK","SEK","NOK","DKK","HUF","RON","BGN","AUD","CAD","NZD","JPY","CNY","HKD","SGD","KRW","INR","BRL","MXN","ZAR","TRY","ILS","IDR","MYR","PHP","THB"};
    private static void exchangeRate() throws Exception {
        SharedPreferences p=preferences();if(System.currentTimeMillis()-p.getLong("usd-time",0)<86400000)return;
        String quote=p.getString("currency","EUR");
        HttpURLConnection connection=(HttpURLConnection)new URL("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml").openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
        double usd=0,rate=quote.equals("EUR")?1:0;
        try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[8192];int n,total=0;while((n=in.read(bytes))!=-1){total+=n;if(total>128*1024)throw new IOException();out.write(bytes,0,n);}
            org.xmlpull.v1.XmlPullParser parser=android.util.Xml.newPullParser();parser.setInput(new java.io.StringReader(new String(out.toByteArray(),StandardCharsets.UTF_8)));
            int event;while((event=parser.next())!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT)if(event==org.xmlpull.v1.XmlPullParser.START_TAG){String code=parser.getAttributeValue(null,"currency"),value=parser.getAttributeValue(null,"rate");if(value!=null){if("USD".equals(code))usd=Double.parseDouble(value);if(quote.equals(code))rate=Double.parseDouble(value);}}
        }finally{connection.disconnect();}
        if(usd>0&&rate>0)p.edit().putString("usd-text",String.format(Locale.getDefault(),"USD %.2f %s",rate/usd,quote)).putLong("usd-time",System.currentTimeMillis()).apply();
    }
    private static void weather() throws Exception {
        SharedPreferences p=preferences();if(System.currentTimeMillis()-p.getLong("weather-time",0)<1800000)return;
        String lat=p.getString("lat",""),lon=p.getString("lon","");if(lat.isEmpty()||lon.isEmpty())return;
        double latitude=Double.parseDouble(lat),longitude=Double.parseDouble(lon);if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||Math.abs(latitude)>90||Math.abs(longitude)>180)return;
        JSONObject current=json("https://api.open-meteo.com/v1/forecast?latitude="+latitude+"&longitude="+longitude+"&current=temperature_2m,weather_code").getJSONObject("current");
        int code=current.optInt("weather_code");String icon=code==0?"☀":code<4?"☁":code<50?"≋":code<70?"☂":code<80?"❄":"☂";
        p.edit().putString("weather-text",icon+" "+Math.round(current.getDouble("temperature_2m"))+"°C").putLong("weather-time",System.currentTimeMillis()).apply();
    }
}
