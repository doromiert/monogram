// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

public final class OmemoSendException extends Exception {
    public enum Reason { NO_DEVICES, NO_TRUSTED_DEVICES, DEVICE_FAILURE }
    public final Reason reason;
    OmemoSendException(Reason reason) { super(reason.name()); this.reason = reason; }
}
