-- SPDX-License-Identifier: GPL-3.0-or-later
-- Optional scheduled delivery. Payloads may contain OMEMO ciphertext; no keys are held here.
local st = require "util.stanza";
local jid = require "util.jid";
local uuid = require "util.uuid";
local ns = "urn:monogram:schedule:0";
local store = module:open_store("monogram_schedule", "keyval");
local queue, load_error = store:get("queue");
if load_error then error("Cannot load scheduled messages: " .. tostring(load_error)); end
queue = queue or {};
local max_delay = 7 * 24 * 60 * 60;
module:add_feature(ns);

local function save() return store:set("queue", queue); end
local function reject(event, condition, text)
    event.origin.send(st.error_reply(event.stanza, "cancel", condition, text));
    return true;
end

module:hook("iq/self/" .. ns .. ":schedule", function(event)
    local origin, iq = event.origin, event.stanza;
    if iq.attr.type ~= "set" then return reject(event, "bad-request"); end
    local request = iq:get_child("schedule", ns);
    local owner = jid.join(origin.username, origin.host);
    local when = tonumber(request.attr.at);
    local message = request:get_child("message", "jabber:client");
    if not when or when ~= math.floor(when) or when < os.time() + 1 or when > os.time() + max_delay
       or not message or message.attr.type ~= "chat" or #tostring(message) > 65536 then
        return reject(event, "bad-request", "Expected a chat message and a delivery time within seven days");
    end
    local recipient = jid.prep(message.attr.to);
    if not recipient or not jid.node(recipient) or jid.resource(recipient) then return reject(event, "jid-malformed"); end
    local count, total = 0, 0;
    for _, item in pairs(queue) do
        total = total + 1;
        if item.owner == owner then count = count + 1; end
    end
    if count >= 100 or total >= 10000 then return reject(event, "resource-constraint"); end
    local id = uuid.generate();
    local payload = st.clone(message);
    payload.attr.from = owner;
    payload.attr.to = recipient;
    payload.attr.xmlns = "jabber:client";
    queue[id] = { owner = owner; at = when; message = st.preserialize(payload) };
    if not save() then queue[id] = nil; return reject(event, "internal-server-error"); end
    origin.send(st.reply(iq):tag("scheduled", { xmlns = ns; id = id; at = tostring(when) }));
    return true;
end);

module:hook("iq/self/" .. ns .. ":cancel", function(event)
    local iq, origin = event.stanza, event.origin;
    if iq.attr.type ~= "set" then return reject(event, "bad-request"); end
    local id = iq:get_child("cancel", ns).attr.id;
    local item = queue[id];
    if not item or item.owner ~= jid.join(origin.username, origin.host) then return reject(event, "item-not-found"); end
    queue[id] = nil;
    if not save() then queue[id] = item; return reject(event, "internal-server-error"); end
    origin.send(st.reply(iq));
    return true;
end);

module:hook("iq/self/" .. ns .. ":scheduled", function(event)
    local iq, origin = event.stanza, event.origin;
    if iq.attr.type ~= "get" then return reject(event, "bad-request"); end
    local reply = st.reply(iq):tag("scheduled", { xmlns = ns });
    local owner = jid.join(origin.username, origin.host);
    for id, item in pairs(queue) do
        if item.owner == owner then reply:tag("item", { id = id; at = tostring(item.at) }):up(); end
    end
    origin.send(reply);
    return true;
end);

module:add_timer(1, function()
    for id, item in pairs(queue) do
        if item.at <= os.time() then
            -- Persist removal before routing. This avoids duplicates after restart, with a small crash-loss window.
            queue[id] = nil;
            if save() then module:send(st.deserialize(item.message)); else queue[id] = item; end
        end
    end
    return 1;
end);
