package org.vosk.demo;

public class HexUtils {
    public static String byteToHexString(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            hexString.append(String.format("%02X", b));
        }
        return hexString.toString().trim();
    }
}
