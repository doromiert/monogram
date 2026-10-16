// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

public final class OmemoSendException extends java.security.GeneralSecurityException {
    public enum Reason { NO_DEVICES, NO_TRUSTED_DEVICES, DEVICE_FAILURE, MODERN_UNAVAILABLE, TRUST_REQUIRED, ROOM_UNSUPPORTED, ROOM_CHANGED, LOOKUP_TIMEOUT }
    public final Reason reason;
    public final String detail;
    OmemoSendException(Reason reason) { this(reason,null); }
    OmemoSendException(Reason reason,String detail) { super(reason.name()); this.reason = reason; this.detail=detail; }
}
