// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.util.*;
import org.json.JSONObject;

/** Converts audited Keystore ciphertext fields at the encrypted-archive boundary, never in live preferences. */
final class MonogramBackupSecrets {
    enum Namespace { XMPP, MAIN, PACKS, GIPHY }
    interface Transform { String apply(String value) throws Exception; }
    private MonogramBackupSecrets() { }

    /** Pass sourceVault::decrypt for export or destinationVault::encrypt for restore staging. */
    static Map<String,Object> preferences(Namespace namespace, Map<String,?> source, Transform transform) throws Exception {
        Map<String,Object> result = new LinkedHashMap<>();
        for (Map.Entry<String,?> entry : source.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            boolean sealed = namespace == Namespace.XMPP && (key.equals("credential") || key.startsWith("room-password-"))
                    || namespace == Namespace.MAIN && key.equals("monogram_translation_api_key")
                    || namespace == Namespace.PACKS && key.equals("token")
                    || namespace == Namespace.GIPHY && key.equals("key");
            if (sealed) {
                if (!(value instanceof String)) throw new IllegalArgumentException("Invalid encrypted preference type.");
                value = string((String)value, transform);
            } else if (namespace == Namespace.XMPP && key.startsWith("room-invitations-")) {
                if (!(value instanceof String)) throw new IllegalArgumentException("Invalid saved invitation type.");
                JSONObject invitations = new JSONObject((String)value);
                Iterator<String> rooms = invitations.keys();
                while (rooms.hasNext()) {
                    JSONObject invitation = invitations.getJSONObject(rooms.next());
                    if (invitation.has("password")) invitation.put("password", string(invitation.getString("password"), transform));
                }
                value = invitations.toString();
            } else if (value instanceof Set) {
                HashSet<String> strings = new HashSet<>();
                for (Object item : (Set<?>)value) {
                    if (!(item instanceof String)) throw new IllegalArgumentException("Invalid preference string set.");
                    strings.add((String)item);
                }
                value = Collections.unmodifiableSet(strings);
            } else if (!(value instanceof String || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Boolean)) {
                throw new IllegalArgumentException("Unsupported preference type.");
            }
            result.put(key, value);
        }
        return result;
    }
    /** room_bookmarks.payload is also AccountVault ciphertext, unlike the other message database columns. */
    static String bookmarkPayload(String value, Transform transform) throws Exception {
        return value == null ? null : string(value, transform);
    }
    private static String string(String value, Transform transform) throws Exception {
        return value.isEmpty() ? "" : transform.apply(value);
    }
}
