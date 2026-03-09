package org.vosk.speechtest;

import static android.content.ContentValues.TAG;

import android.os.Looper;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TcpClient {

    private final String serverIp;
    private final int serverPort;

    public Socket clientSocket;
    private volatile boolean isTcpConnected = false;

    private final ExecutorService tcpService;
    private Thread rxThread;
    private volatile boolean rxRunning = false;

    // ===== 等 ACK 用 =====
    private final Object ackLock = new Object();
    private CompletableFuture<Boolean> pendingAck = null;

    public TcpClient(String serverIp, int serverPort, ExecutorService tcpService) {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.tcpService = (tcpService != null) ? tcpService : Executors.newSingleThreadExecutor();
    }

    public boolean isTcpConnected() {
        return isTcpConnected;
    }

    public synchronized void initializeTcpConnection() {
        if (isTcpConnected) {
            Log.d(TAG, "已經連接，不需要重新建立連接。");
            return;
        }

        tcpService.submit(() -> {
            while (!isTcpConnected) {
                try {
                    Log.d(TAG, "嘗試連接到服務器... " + serverIp + ":" + serverPort);

                    // ★ 改：不要寫死 IP/Port，改用欄位
                    clientSocket = new Socket(serverIp, serverPort);
                    clientSocket.setKeepAlive(true);

                    isTcpConnected = true;
                    Log.d(TAG, "TCP 連接成功");

                    startReceiverIfNeeded();
                } catch (IOException e) {
                    Log.e(TAG, "TCP 連接失敗: " + e.getMessage(), e);
                    isTcpConnected = false;

                    android.os.Handler handler = new android.os.Handler(Looper.getMainLooper());
                    handler.postDelayed(this::initializeTcpConnection, 3000);
                    break;
                }
            }
        });
    }

    private void startReceiverIfNeeded() {
        if (rxThread != null && rxThread.isAlive()) return;

        rxRunning = true;
        rxThread = new Thread(() -> {
            try {
                InputStream in = clientSocket.getInputStream();

                // 以 0xA5..0xFA 組 frame
                java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream();
                boolean inFrame = false;

                while (rxRunning && clientSocket != null && !clientSocket.isClosed()) {
                    int b = in.read();
                    if (b == -1) break;

                    int ub = b & 0xFF;

                    if (!inFrame) {
                        if (ub == 0xA5) {
                            inFrame = true;
                            frame.reset();
                            frame.write(ub);
                        }
                        continue;
                    } else {
                        frame.write(ub);
                        if (ub == 0xFA) {
                            byte[] pkt = frame.toByteArray();
                            inFrame = false;

                            // ★ 收到回傳（ACK）
                            onAckPacket(pkt);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Receiver thread ended: " + e.getMessage(), e);
            } finally {
                isTcpConnected = false;
                rxRunning = false;

                // 若有人在等 ACK，直接讓他失敗
                synchronized (ackLock) {
                    if (pendingAck != null && !pendingAck.isDone()) pendingAck.complete(false);
                    pendingAck = null;
                }
            }
        }, "TCP-RX");
        rxThread.start();
    }

    private void onAckPacket(byte[] pkt) {
        if (pkt.length < 6) return;

        if ((pkt[0] & 0xFF) != 0xA5) return;
        if ((pkt[1] & 0xFF) != 0x90) return;

        int result = pkt[2] & 0xFF;   // 01 or 00
        boolean ok = (result == 0x01);

        synchronized (ackLock) {
            if (pendingAck != null && !pendingAck.isDone()) {
                pendingAck.complete(ok);
                pendingAck = null;
            }
        }
    }


    /** 原本的送包（不等待 ACK）保留 */
    public synchronized void sendPacket(byte[] packet, long speechEndTime, long packetGenEndTime) {
        tcpService.execute(() -> {
            if (clientSocket != null && isTcpConnected && !clientSocket.isClosed()) {
                try {
                    DataOutputStream outputStream = new DataOutputStream(clientSocket.getOutputStream());
                    outputStream.write(packet);
                    outputStream.flush();

                    long sendEndTime = System.currentTimeMillis();
                    long speechToGenTime = packetGenEndTime - speechEndTime;
                    long genToSendTime = sendEndTime - packetGenEndTime;
                    long total = speechToGenTime + genToSendTime;

                    Log.d(TAG, "語音辨識到封包生成耗時: " + speechToGenTime + " 毫秒");
                    Log.d(TAG, "封包生成到傳輸完成耗時: " + genToSendTime + " 毫秒");
                    Log.d(TAG, "總處理時間: " + total + " 毫秒");
                } catch (IOException e) {
                    Log.e(TAG, "數據發送失敗: " + e.getMessage(), e);
                    isTcpConnected = false;
                }
            } else {
                Log.e(TAG, "連接未建立，無法發送數據");
            }
        });
    }

    /** ★ 新增：送包並等待 ACK（timeoutMs 內收到回傳就 true，否則 false） */
    public CompletableFuture<Boolean> sendPacketAwaitAck(byte[] packet, long timeoutMs) {
        CompletableFuture<Boolean> fut = new CompletableFuture<>();

        tcpService.execute(() -> {
            if (clientSocket == null || !isTcpConnected || clientSocket.isClosed()) {
                fut.complete(false);
                return;
            }

            try {
                synchronized (ackLock) {
                    // 如果上一筆還在等，先讓它失敗（避免卡死）
                    if (pendingAck != null && !pendingAck.isDone()) pendingAck.complete(false);
                    pendingAck = fut;
                }

                DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());
                out.write(packet);
                out.flush();

                // timeout
                tcpService.execute(() -> {
                    try { Thread.sleep(timeoutMs); } catch (InterruptedException ignored) {}
                    synchronized (ackLock) {
                        if (pendingAck == fut && !fut.isDone()) {
                            fut.complete(false);
                            pendingAck = null;
                        }
                    }
                });

            } catch (IOException e) {
                Log.e(TAG, "發送失敗: " + e.getMessage(), e);
                isTcpConnected = false;
                synchronized (ackLock) {
                    if (pendingAck == fut && !fut.isDone()) fut.complete(false);
                    if (pendingAck == fut) pendingAck = null;
                }
            }
        });

        return fut;
    }

    public void closeTcpConnection() {
        rxRunning = false;
        if (rxThread != null) rxThread.interrupt();

        tcpService.execute(() -> {
            try {
                if (clientSocket != null && clientSocket.isConnected()) {
                    try {
                        DataOutputStream outputStream = new DataOutputStream(clientSocket.getOutputStream());
                        byte[] closeCode = new byte[]{(byte) 0xA5, (byte) 0x00, (byte) 0xFA};
                        outputStream.write(closeCode);
                        outputStream.flush();
                        Log.d(TAG, "關閉代碼已發送: " + HexUtils.byteToHexString(closeCode));
                    } catch (IOException e) {
                        Log.e(TAG, "發送結束代碼失敗: " + e.getMessage(), e);
                    }

                    clientSocket.close();
                    isTcpConnected = false;
                    Log.d(TAG, "已關閉連接");
                }
            } catch (IOException e) {
                Log.e(TAG, "關閉連接失敗: " + e.getMessage(), e);
            } finally {
                synchronized (ackLock) {
                    if (pendingAck != null && !pendingAck.isDone()) pendingAck.complete(false);
                    pendingAck = null;
                }
            }
        });
    }
}
