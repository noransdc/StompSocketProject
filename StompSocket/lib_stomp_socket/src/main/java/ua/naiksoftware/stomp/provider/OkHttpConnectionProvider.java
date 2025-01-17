package ua.naiksoftware.stomp.provider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import ua.naiksoftware.stomp.dto.LifecycleEvent;

public class OkHttpConnectionProvider extends AbstractConnectionProvider {

    public static final String TAG = "OkHttpConnProvider";

    private final String mUri;
    @NonNull
    private final Map<String, String> mConnectHttpHeaders;
    private final OkHttpClient mOkHttpClient;

    @Nullable
    private WebSocket openSocket;

    public OkHttpConnectionProvider(String uri, @Nullable Map<String, String> connectHttpHeaders, OkHttpClient okHttpClient) {
        super();
        mUri = uri;
        mConnectHttpHeaders = connectHttpHeaders != null ? connectHttpHeaders : new HashMap<>();
        mOkHttpClient = okHttpClient;
    }

    @Override
    public void rawDisconnect() {
        if (openSocket != null) {
            openSocket.close(1000, "");
        }
    }

    @Override
    protected void createWebSocketConnection() {
        Request.Builder requestBuilder = new Request.Builder()
                .url(mUri);

        addConnectionHeadersToBuilder(requestBuilder, mConnectHttpHeaders);

        openSocket = mOkHttpClient.newWebSocket(requestBuilder.build(),
                new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket webSocket, @NonNull Response response) {
                        LifecycleEvent openEvent = new LifecycleEvent(LifecycleEvent.Type.OPENED);

                        TreeMap<String, String> headersAsMap = headersAsMap(response);

                        openEvent.setHandshakeResponseHeaders(headersAsMap);
                        emitLifecycleEvent(openEvent);
                    }

                    @Override
                    public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
//                        Logan.d("mOkHttpClient.newWebSocket onMessage String", text);
                        emitMessage(text);
                    }

                    @Override
                    public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
//                        Logan.d("mOkHttpClient onMessage ByteString", bytes);

                        try {

                            byte[] toByteArray = bytes.toByteArray();
//                            Logan.w("mOkHttpClient onMessage toByteArray", Arrays.toString(toByteArray));

                            String s1 = new String(toByteArray, StandardCharsets.US_ASCII);
//                            Logan.w("mOkHttpClient onMessage s1", s1);

                            String[] splitArr = s1.split("\n\n");
                            if (splitArr.length < 2){
                                return;
                            }
                            String prefix = splitArr[0] + "\n\n";
//                            Logan.w("mOkHttpClient onMessage prefix", prefix);

                            byte[] prefixArr = prefix.getBytes(StandardCharsets.US_ASCII);
//                            Logan.w("mOkHttpClient onMessage prefixArr", Arrays.toString(prefixArr));

                            byte[] suffixArr = Arrays.copyOfRange(toByteArray, prefixArr.length, toByteArray.length);
//                            Logan.w("mOkHttpClient onMessage suffixArr", Arrays.toString(suffixArr));

                            byte[] dataArr = removeStompTerminator(suffixArr);
//                            Logan.w("mOkHttpClient onMessage dataArr", Arrays.toString(dataArr));

                            String result = prefix + uncompress(dataArr);
//                            Logan.w("mOkHttpClient onMessage result", result);

                            byte[] resultArr = result.getBytes(StandardCharsets.UTF_8);
                            byte[] extendedArray = Arrays.copyOf(resultArr, resultArr.length + 1);
                            extendedArray[extendedArray.length - 1] = 0x00;

//                            Logan.w("rr", new String(extendedArray, StandardCharsets.UTF_8));

                            emitMessage(new String(extendedArray, StandardCharsets.UTF_8));

                        } catch (Exception e){
                            e.printStackTrace();
                        }

                    }

                    @Override
                    public void onClosed(WebSocket webSocket, int code, String reason) {
                        openSocket = null;
                        emitLifecycleEvent(new LifecycleEvent(LifecycleEvent.Type.CLOSED));
                    }

                    @Override
                    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                        // in OkHttp, a Failure is equivalent to a JWS-Error *and* a JWS-Close
                        emitLifecycleEvent(new LifecycleEvent(LifecycleEvent.Type.ERROR, new Exception(t)));
                        openSocket = null;
                        emitLifecycleEvent(new LifecycleEvent(LifecycleEvent.Type.CLOSED));
                    }

                    @Override
                    public void onClosing(final WebSocket webSocket, final int code, final String reason) {
                        webSocket.close(code, reason);
                    }
                }

        );
    }

    private String uncompress(byte[] input){
        Inflater inflater = new Inflater();
        inflater.setInput(input);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(input.length);

        byte[] buffer = new byte[1024];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                outputStream.write(buffer, 0, count);
            }
            outputStream.close();
        } catch (DataFormatException | IOException e) {
            e.printStackTrace();
        } finally {
            inflater.end();
        }

        return outputStream.toString();
    }

    private byte[] removeStompTerminator(byte[] data) {
        // 检查数组是否以终止符 0x00 结尾
        if (data.length > 0 && data[data.length - 1] == 0x00) {
            return Arrays.copyOfRange(data, 0, data.length - 1); // 去除最后一个字节
        }
        return data; // 无终止符，直接返回原始数组
    }

    @Override
    protected void rawSend(String stompMessage) {
        openSocket.send(stompMessage);
    }

    @Nullable
    @Override
    protected Object getSocket() {
        return openSocket;
    }

    @NonNull
    private TreeMap<String, String> headersAsMap(@NonNull Response response) {
        TreeMap<String, String> headersAsMap = new TreeMap<>();
        Headers headers = response.headers();
        for (String key : headers.names()) {
            headersAsMap.put(key, headers.get(key));
        }
        return headersAsMap;
    }

    private void addConnectionHeadersToBuilder(@NonNull Request.Builder requestBuilder, @NonNull Map<String, String> mConnectHttpHeaders) {
        for (Map.Entry<String, String> headerEntry : mConnectHttpHeaders.entrySet()) {
            requestBuilder.addHeader(headerEntry.getKey(), headerEntry.getValue());
        }
    }
}
