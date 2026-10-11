package app.tether;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Minimal client for the PeerJS signalling protocol (PeerJS 1.5.x, hosted server 0.peerjs.com),
 * so this app can place and answer calls with the web page. All callbacks run on the main thread.
 */
final class PeerSignal {
    interface Listener {
        void onMessage(JSONObject message);

        void onClosed(String reason);
    }

    private static final String URL_FORMAT =
            "wss://0.peerjs.com:443/peerjs?key=peerjs&id=%s&token=%s&version=1.5.5";
    private static final long HEARTBEAT_MS = 5000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient();
    private final Listener listener;
    private WebSocket socket;
    private boolean closed;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (closed || socket == null) return;
            socket.send("{\"type\":\"HEARTBEAT\"}");
            main.postDelayed(this, HEARTBEAT_MS);
        }
    };

    PeerSignal(Listener listener) {
        this.listener = listener;
    }

    void connect(String id) {
        String token = Long.toString(Math.abs(new Random().nextLong()), 36);
        Request request = new Request.Builder().url(String.format(URL_FORMAT, id, token)).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                main.post(() -> main.postDelayed(heartbeat, HEARTBEAT_MS));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    main.post(() -> {
                        if (!closed) listener.onMessage(message);
                    });
                } catch (JSONException ignored) {
                    // Ignore malformed server messages, as the PeerJS client does.
                }
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                main.post(() -> fail("signalling closed"));
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                main.post(() -> fail("signalling error: " + t.getMessage()));
            }
        });
    }

    void send(JSONObject message) {
        if (socket != null && !closed) socket.send(message.toString());
    }

    private void fail(String reason) {
        if (closed) return;
        close();
        listener.onClosed(reason);
    }

    void close() {
        closed = true;
        main.removeCallbacks(heartbeat);
        if (socket != null) {
            socket.close(1000, null);
            socket = null;
        }
    }
}
