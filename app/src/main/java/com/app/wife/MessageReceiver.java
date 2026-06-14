package com.wife.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;

public class MessageReceiver implements Runnable {
    private static final String TAG = "MessageReceiver";

    private final Context context;
    private final Socket socket;
    private final boolean isControl;

    public MessageReceiver(Context context, Socket socket, boolean isControl) {
        this.context = context;
        this.socket = socket;
        this.isControl = isControl;
    }

    @Override
    public void run() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Log.d(TAG, "Received payload: " + line);

                try {
                    JsonObject jsonObject = JsonParser.parseString(line).getAsJsonObject();
                    String type = jsonObject.has("type") ? jsonObject.get("type").getAsString() : "";

                    if (isControl) {
                        handleControlMessage(type, jsonObject);
                    } else {
                        handleTextMessage(type, jsonObject);
                    }
                } catch (Exception parseException) {
                    Log.e(TAG, "Error parsing incoming packet: " + parseException.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Socket read error: " + e.getMessage());
        } finally {
            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (Exception ex) { ex.printStackTrace(); }
        }
    }

    private void handleControlMessage(String valType, JsonObject json) {
        String peerIp = socket.getInetAddress().getHostAddress();
        
        // Dynamically update and register client IP address on any control message received
        ConnectionManager.getInstance(context).updatePeerIpFromAccept(peerIp);
        
        // Check for Call Signals
        if (valType.startsWith("CALL_") || valType.startsWith("VIDEO_CALL_")) {
            CallSignalingManager.getInstance(context).handleReceivedSignal(valType, json, peerIp);
            return;
        }

        // Check for Heartbeat
        if ("heartbeat".equals(valType)) {
            HeartbeatManager.getInstance(context).onHeartbeatReceived(peerIp);
            return;
        }

        if ("handshake".equals(valType)) {
            Log.d(TAG, "Handshake received, client ip updated.");
            // ConnectionManager.getInstance(context).updatePeerIpFromAccept(peerIp); // Redundant now as it is executed on method entry
        }
    }

    private void handleTextMessage(String valType, JsonObject json) {
        if ("message".equals(valType)) {
            String id = json.get("id").getAsString();
            String sender = json.get("sender").getAsString();
            long time = json.get("time").getAsLong();
            String text = json.get("text").getAsString();

            // Store in Database
            MessageEntity entity = new MessageEntity(sender, Utils.getDeviceId(context), text, time);
            RoomDatabaseManager.getInstance(context).messageDao().insert(entity);

            // Notify Active Chat Screen via Local Singleton / Broadcast
            ChatManager.getInstance(context).notifyMessageReceived(entity);
        }
    }
}