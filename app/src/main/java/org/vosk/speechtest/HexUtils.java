package org.vosk.speechtest;

public class HexUtils {
    public static String byteToHexString(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            hexString.append(String.format("%02X", b));
        }
        return hexString.toString().trim();
    }
    public static String Conversion(int data) {
        return String.format("%02X", data);
    }
}
