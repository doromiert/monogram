// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.util.*;
import org.json.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;

/** Private local selection snapshots; does not resolve contacts, fetch files or send anything. */
final class MonogramSelectionDrafts {
    private MonogramSelectionDrafts() {}
    static JSONObject contact(Object selected) throws Exception {
        if(selected instanceof TLRPC.User) return new JSONObject().put("type","user").put("user",user((TLRPC.User)selected));
        ContactsController.Contact contact=(ContactsController.Contact)selected;
        JSONObject out=new JSONObject().put("type","phonebook").put("id",contact.contact_id).put("key",contact.key).put("provider",contact.provider).put("good",contact.isGoodProvider)
                .put("first",contact.first_name).put("last",contact.last_name).put("filled",contact.namesFilled).put("imported",contact.imported)
                .put("phones",new JSONArray(contact.phones)).put("short",new JSONArray(contact.shortPhones)).put("types",new JSONArray(contact.phoneTypes)).put("deleted",new JSONArray(contact.phoneDeleted));
        if(contact.user!=null) out.put("user",user(contact.user));return out;
    }
    static Object contact(JSONObject in) throws Exception {
        if(in.getString("type").equals("user")) return readUser(in.getJSONObject("user"));
        ContactsController.Contact contact=new ContactsController.Contact();contact.contact_id=in.getInt("id");contact.key=in.isNull("key") ? null : in.optString("key");contact.provider=in.optString("provider");contact.isGoodProvider=in.optBoolean("good");
        contact.first_name=in.optString("first");contact.last_name=in.optString("last");contact.namesFilled=in.optBoolean("filled");contact.imported=in.optInt("imported");
        fill(in.getJSONArray("phones"),contact.phones);fill(in.getJSONArray("short"),contact.shortPhones);fill(in.getJSONArray("types"),contact.phoneTypes);
        JSONArray deleted=in.getJSONArray("deleted");for(int i=0;i<deleted.length();i++) contact.phoneDeleted.add(deleted.getInt(i));
        if(in.has("user")) contact.user=readUser(in.getJSONObject("user"));return contact;
    }
    static String messageKey(MessageObject message) { return message.getDialogId()+"/"+message.getId(); }
    static JSONObject message(MessageObject source) throws Exception {
        TLRPC.Message message=source.messageOwner;SerializedData data=new SerializedData();String nativeData;
        try { message.serializeToStream(data);nativeData=encode(data); } finally { data.cleanup(); }
        return new JSONObject().put("native",nativeData).put("dialog",source.getDialogId()).put("id",source.getId()).put("path",message.attachPath).put("params",message.params==null ? new JSONObject() : new JSONObject(message.params));
    }
    static MessageObject message(int account,JSONObject in) throws Exception {
        SerializedData data=decode(in.getString("native"));try {
            TLRPC.Message message=TLRPC.Message.TLdeserialize(data,data.readInt32(true),true);message.id=in.getInt("id");message.dialog_id=in.getLong("dialog");message.attachPath=in.optString("path");
            message.params=new HashMap<>();JSONObject params=in.getJSONObject("params");Iterator<String> keys=params.keys();while(keys.hasNext()) { String key=keys.next();message.params.put(key,params.getString(key)); }
            return new MessageObject(account,message,false,false);
        } finally { data.cleanup(); }
    }
    private static JSONObject user(TLRPC.User user) throws Exception {
        SerializedData data=new SerializedData();String nativeData;try { user.serializeToStream(data);nativeData=encode(data); } finally { data.cleanup(); }
        JSONArray reasons=new JSONArray();for(TLRPC.RestrictionReason reason:user.restriction_reason) reasons.put(new JSONObject().put("text",reason.text).put("reason",reason.reason).put("platform",reason.platform));
        return new JSONObject().put("native",nativeData).put("reasons",reasons);
    }
    private static TLRPC.User readUser(JSONObject in) throws Exception {
        SerializedData data=decode(in.getString("native"));try {
            TLRPC.User user=TLRPC.User.TLdeserialize(data,data.readInt32(true),true);user.restriction_reason.clear();JSONArray reasons=in.getJSONArray("reasons");
            for(int i=0;i<reasons.length();i++) { JSONObject saved=reasons.getJSONObject(i);TLRPC.RestrictionReason reason=new TLRPC.RestrictionReason();reason.text=saved.optString("text");reason.reason=saved.optString("reason");reason.platform=saved.optString("platform");user.restriction_reason.add(reason); }return user;
        } finally { data.cleanup(); }
    }
    private static void fill(JSONArray array,ArrayList<String> values) throws Exception { for(int i=0;i<array.length();i++) values.add(array.getString(i)); }
    private static String encode(SerializedData data) { return android.util.Base64.encodeToString(data.toByteArray(),android.util.Base64.NO_WRAP); }
    private static SerializedData decode(String value) { return new SerializedData(android.util.Base64.decode(value,android.util.Base64.DEFAULT)); }
}
