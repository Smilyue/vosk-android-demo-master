package org.vosk.demo;

import static android.content.ContentValues.TAG;

import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;

public class TcpClient {
    private  final String serverIp;
    private  final  int serverPort;
    public Socket clientSocket;
    private boolean isTcpConnected = false;
    private ExecutorService tcpService = Executors.newFixedThreadPool(6);
    public TcpClient(String serverIp, int serverPort,ExecutorService tcpService) {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.tcpService = tcpService;
    }
    public boolean isTcpConnected(){
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
                    Log.d("Socket", "嘗試連接到服務器...");
                    String serverIp = "192.168.0.79";
                    int serverPort = 8080;
                    clientSocket = new Socket(serverIp, serverPort);
                    clientSocket.setKeepAlive(true);
                    isTcpConnected = true;
                    Log.d("TCP 連接", "成功");


                } catch (IOException e) {
                    Log.e("TCP 連接", "IOException: " + e.getMessage(), e);
                    isTcpConnected = false;
                    android.os.Handler handler = new android.os.Handler(Looper.getMainLooper());
                    handler.postDelayed(this::initializeTcpConnection, 3000);
                }
            }
        });
    }

    public synchronized void sendPacket(String packet) {
        tcpService.execute(() -> {
            if (clientSocket != null && isTcpConnected && !clientSocket.isClosed()) {
                try {
                    DataOutputStream outputStream = new DataOutputStream(clientSocket.getOutputStream());
                    outputStream.write(packet.getBytes("UTF-8"));
                    outputStream.flush();
                    Log.d(TAG, "數據發送成功: " + packet);
                } catch (IOException e) {
                    Log.e(TAG, "數據發送失敗: " + e.getMessage(), e);
                    isTcpConnected =false;

                }
            } else {
                Log.e(TAG, "連接未建立，無法發送數據");
            }
        });
    }

    public void closeTcpConnection() {
        tcpService.execute(() -> {
            try {
                if (clientSocket != null && clientSocket.isConnected()) {
                    try {
                        DataOutputStream outputStream = new DataOutputStream(clientSocket.getOutputStream());
                        String closeCode = "A5 00 FA";
                        outputStream.write(closeCode.getBytes("UTF-8"));
                        outputStream.flush();
                        Log.d(TAG, "關閉代碼已發送: " + closeCode);
                    }catch(IOException e){
                        Log.e(TAG,"發送結束代碼失敗: " + e.getMessage(),e);
                    }
                    clientSocket.close();
                    isTcpConnected = false;
                    Log.d(TAG, "已關閉連接");
                }
            } catch (IOException e) {
                Log.e(TAG, "關閉連接失敗: " + e.getMessage(), e);
            }
        });
    }

}
