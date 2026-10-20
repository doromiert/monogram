// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

/** Local-only detail for a successfully created room with unsuccessful invitations. */
public final class MonogramInvitedUsers extends org.telegram.tgnet.TLRPC.TL_messages_invitedUsers {
    public String failure;
}
