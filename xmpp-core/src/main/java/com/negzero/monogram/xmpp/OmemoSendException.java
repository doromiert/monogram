// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

public final class OmemoSendException extends java.security.GeneralSecurityException {
    public enum Reason { NO_DEVICES, NO_TRUSTED_DEVICES, DEVICE_FAILURE, MODERN_UNAVAILABLE, TRUST_REQUIRED }
    public final Reason reason;
    OmemoSendException(Reason reason) { super(reason.name()); this.reason = reason; }
}
