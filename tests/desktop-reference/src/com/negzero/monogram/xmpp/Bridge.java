// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** In-memory fictional peer. Uses the unchanged mobile JNI boundary, not desktop FFI. */
public final class Bridge {
    static final Base64.Encoder B64=Base64.getEncoder();
    static final Base64.Decoder UN64=Base64.getDecoder();
    static final class Records implements Omemo2Records {
        Map<String,byte[]> saved=new HashMap<>(),pending;
        String key(int kind,String peer,int id){return kind+"\n"+peer+"\n"+id;}
        public byte[] load(int kind,String peer,int id){byte[] bytes=pending.get(key(kind,peer,id));return bytes==null?null:bytes.clone();}
        public void save(int kind,String peer,int id,byte[] bytes){if(bytes==null)pending.remove(key(kind,peer,id));else pending.put(key(kind,peer,id),bytes.clone());}
        public int[] ids(int kind){return pending.keySet().stream().filter(k->k.startsWith(kind+"\n\n")).mapToInt(k->Integer.parseInt(k.substring(k.lastIndexOf('\n')+1))).toArray();}
        public void commit(){saved=pending;pending=null;}
        public void close(){pending=null;}
        void begin(){pending=new HashMap<>(saved);}
    }
    public static void main(String[] args)throws Exception {
        final int local=202;Records records=new Records();
        BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));String line;
        while((line=in.readLine())!=null){if(line.length()>1024*1024)throw new IllegalArgumentException("fixture limit");String[] p=line.split("\t",-1);records.begin();
            try{String result;
                switch(p[0]){
                    case "INIT":{
                        records.pending.clear();
                        byte[][] identity=Omemo2Native.identity(),pre=Omemo2Native.preKey(11),signed=Omemo2Native.signedPreKey(identity[0],22,1);
                        records.save(0,"",0,identity[0]);records.save(0,"",1,identity[1]);records.save(1,"",11,pre[0]);records.save(2,"",22,signed[0]);
                        result=B64.encodeToString(identity[1])+"\t"+B64.encodeToString(pre[1])+"\t"+B64.encodeToString(signed[1])+"\t"+B64.encodeToString(signed[2]);break;
                    }
                    case "TRUST":records.save(5,p[1],Integer.parseInt(p[2]),UN64.decode(p[3]));result="trusted";break;
                    case "BUILD":Omemo2Native.build(records,local,p[1],Integer.parseInt(p[2]),Long.parseLong(p[3]),UN64.decode(p[4]),Long.parseLong(p[5]),UN64.decode(p[6]),UN64.decode(p[7]),UN64.decode(p[8]));result="built";break;
                    case "ENCRYPT":{byte[][] message=Omemo2Native.encrypt(records,local,p[1],Integer.parseInt(p[2]),UN64.decode(p[3]));result=(message[0][0]==1?"1":"0")+"\t"+B64.encodeToString(message[1]);break;}
                    case "DECRYPT":result=B64.encodeToString(Omemo2Native.decrypt(records,local,p[1],Integer.parseInt(p[2]),UN64.decode(p[4]),p[3].equals("1")));break;
                    default:throw new IllegalArgumentException("fixture operation");
                }
                records.commit();System.out.println("OK\t"+result);
            }catch(Exception error){records.close();System.out.println("ERR\t"+error.getClass().getSimpleName());}
            System.out.flush();
        }
    }
}
