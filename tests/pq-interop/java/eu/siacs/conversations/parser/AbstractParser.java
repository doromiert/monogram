// SPDX-License-Identifier: AGPL-3.0-only
// JVM interoperability fixture; never included in the APK.
package eu.siacs.conversations.parser;
public final class AbstractParser { public static long parseTimestamp(String value) throws java.text.ParseException {
 try{return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli();}catch(java.time.format.DateTimeParseException error){throw new java.text.ParseException("Invalid timestamp",0);}
} }
