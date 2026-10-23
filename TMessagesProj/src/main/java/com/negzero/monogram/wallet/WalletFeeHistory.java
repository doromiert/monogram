// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;

import com.negzero.monogram.xmpp.MoneroPayment;
import org.json.*;
import java.math.BigInteger;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.net.ssl.HttpsURLConnection;
import org.telegram.messenger.AndroidUtilities;

/** Bounded public chain sampling from the user's selected HTTPS node. No wallet identifiers. */
public final class WalletFeeHistory {
    public static final class Point {
        public final long time;public final BigInteger fee;public final int samples,slot;
        Point(long time,BigInteger fee,int samples,int slot){this.time=time;this.fee=fee;this.samples=samples;this.slot=slot;}
    }
    public static final class Snapshot {
        public final List<Point> points;public final long fetched;
        Snapshot(List<Point> points,long fetched){this.points=Collections.unmodifiableList(points);this.fetched=fetched;}
    }
    private static final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread thread=new Thread(r,"Monogram fee history");thread.setDaemon(true);return thread;});
    private static final Map<String,Snapshot> cache=new HashMap<>(); // Worker only.
    public static Future<?> load(String node,MoneroPayment.Network network,Consumer<Snapshot> success,Consumer<Exception> failure){
        return worker.submit(()->{
            try{
                String key=network.name()+node;Snapshot value=cache.get(key);
                if(value==null || System.currentTimeMillis()-value.fetched>10*60*1000L){value=fetch(node,network);cache.put(key,value);}
                Snapshot result=value;if(!Thread.currentThread().isInterrupted())AndroidUtilities.runOnUIThread(()->success.accept(result));
            }catch(Exception error){if(!Thread.currentThread().isInterrupted())AndroidUtilities.runOnUIThread(()->failure.accept(error));}
        });
    }
    static Snapshot fetch(String endpoint,MoneroPayment.Network network)throws Exception {
        URI uri=new URI(endpoint);
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || (!uri.getPath().isEmpty() && !uri.getPath().equals("/")))throw new IOException("Use a valid HTTPS node.");
        String node=endpoint.endsWith("/") ? endpoint.substring(0,endpoint.length()-1) : endpoint;
        long now=System.currentTimeMillis()/1000,deadline=System.currentTimeMillis()+25000;
        JSONObject info=request(node,"/get_info",new JSONObject(),deadline);long height=info.getLong("height");
        boolean matches=network==MoneroPayment.Network.MAINNET ? info.optBoolean("mainnet") : network==MoneroPayment.Network.TESTNET ? info.optBoolean("testnet") : info.optBoolean("stagenet");
        if(!matches || height<2 || !info.optBoolean("synchronized"))throw new IOException("The selected node is not synced on this network.");
        // Restricted public daemons cap a header-range request at 1,000 entries.
        JSONArray headers=rpc(node,"get_block_headers_range",new JSONObject().put("start_height",Math.max(0,height-900)).put("end_height",height-1).put("fill_pow_hash",false),deadline).getJSONArray("headers");
        if(headers.length()>900)throw new IOException("Invalid fee history response.");
        JSONObject[] buckets=new JSONObject[24];long[] distances=new long[24];Arrays.fill(distances,Long.MAX_VALUE);
        for(int i=0;i<headers.length();i++){
            JSONObject header=headers.getJSONObject(i);long timestamp=header.getLong("timestamp"),age=now-timestamp;
            if(age<0 || age>=86400 || header.optInt("num_txes")<=0)continue;
            int bucket=23-(int)(age/3600);long center=now-86400+bucket*3600+1800,distance=Math.abs(timestamp-center);
            if(distance<distances[bucket]){distances[bucket]=distance;buckets[bucket]=header;}
        }
        LinkedHashMap<String,Integer> selected=new LinkedHashMap<>();
        for(int i=0;i<24;i++)if(buckets[i]!=null){
            try{
                JSONArray hashes=rpc(node,"get_block",new JSONObject().put("height",buckets[i].getLong("height")),deadline).optJSONArray("tx_hashes");
                if(hashes==null || hashes.length()==0)continue;
                for(int index:new int[]{0,hashes.length()/2,hashes.length()-1}){String hash=hashes.getString(index);if(hash.matches("[a-fA-F0-9]{64}"))selected.put(hash,i);}
            }catch(Exception unavailable){if(System.currentTimeMillis()>=deadline || Thread.currentThread().isInterrupted())break;}
        }
        if(selected.isEmpty())throw new IOException("Historical fee samples unavailable.");
        // Leave enough time for the bounded final batch even when some block calls were slow.
        JSONObject transactions=request(node,"/get_transactions",new JSONObject().put("txs_hashes",new JSONArray(selected.keySet())).put("decode_as_json",true).put("prune",true),System.currentTimeMillis()+10000);
        JSONArray rows=transactions.optJSONArray("txs");if(rows==null || rows.length()>72)throw new IOException("Historical transactions unavailable.");
        ArrayList<ArrayList<BigInteger>> fees=new ArrayList<>();for(int i=0;i<24;i++)fees.add(new ArrayList<>());
        for(int i=0;i<rows.length();i++)try{
            JSONObject row=rows.getJSONObject(i);Integer bucket=selected.get(row.getString("tx_hash"));if(bucket==null || row.optBoolean("in_pool"))continue;
            JSONObject tx=new JSONObject(row.getString("as_json"));BigInteger fee=new BigInteger(tx.getJSONObject("rct_signatures").get("txnFee").toString());
            if(fee.signum()>0 && fee.compareTo(WalletFeeMath.MAX)<=0)fees.get(bucket).add(fee);
        }catch(Exception malformed){/* Missing or malformed samples remain gaps, never invented zeroes. */}
        ArrayList<Point> points=new ArrayList<>();
        for(int i=0;i<24;i++)if(!fees.get(i).isEmpty())points.add(new Point(buckets[i].getLong("timestamp"),WalletFeeMath.median(fees.get(i)),fees.get(i).size(),i));
        if(points.isEmpty())throw new IOException("Historical fee samples unavailable.");
        return new Snapshot(points,System.currentTimeMillis());
    }
    private static JSONObject rpc(String node,String method,JSONObject params,long deadline)throws Exception {
        JSONObject reply=request(node,"/json_rpc",new JSONObject().put("jsonrpc","2.0").put("id","fee-history").put("method",method).put("params",params),deadline);
        if(reply.has("error") || !reply.has("result"))throw new IOException("The node does not provide fee history.");return reply.getJSONObject("result");
    }
    private static JSONObject request(String node,String path,JSONObject body,long deadline)throws Exception {
        if(Thread.currentThread().isInterrupted() || System.currentTimeMillis()>=deadline)throw new IOException("Fee history request timed out.");
        HttpsURLConnection connection=(HttpsURLConnection)new URL(node+path).openConnection();
        try{
            int timeout=(int)Math.max(1000,Math.min(8000,deadline-System.currentTimeMillis()));connection.setConnectTimeout(timeout);connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setRequestProperty("Content-Type","application/json");connection.setDoOutput(true);
            byte[] data=body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(data.length);
            try(OutputStream out=connection.getOutputStream()){out.write(data);}
            if(connection.getResponseCode()!=200)throw new IOException("Fee history unavailable from this node.");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
            try(InputStream in=connection.getInputStream()){int count;while((count=in.read(buffer))!=-1){if(bytes.size()+count>6*1024*1024 || System.currentTimeMillis()>deadline || Thread.currentThread().isInterrupted())throw new IOException("Fee history response exceeded its limit.");bytes.write(buffer,0,count);}}
            JSONObject reply=new JSONObject(bytes.toString("UTF-8"));if(reply.has("status") && !"OK".equals(reply.optString("status")))throw new IOException("Fee history unavailable from this node.");return reply;
        }finally{connection.disconnect();}
    }
}
