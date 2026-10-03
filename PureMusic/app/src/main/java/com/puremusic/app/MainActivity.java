package com.puremusic.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final String AUTH_BASE = "https://auth.openai.com";
    private static final String API_BASE = "https://api.openai.com/v1";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String DYNAMIC_CLIENT_ID = "dynamic_agent_client";
    private static final String SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final String STORE = "evan_companion";
    private static final String KEY_ALIAS = "evan_companion_secure_store";

    private WebView webView;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final SecureRandom random = new SecureRandom();
    private SharedPreferences prefs;
    private volatile boolean streaming = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(STORE, MODE_PRIVATE);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "No browser available", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                emitConnectionState();
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    public final class Bridge {
        @JavascriptInterface
        public void startLogin() {
            io.execute(() -> {
                try {
                    beginOAuth();
                } catch (Exception e) {
                    emit("onAuthError", safeMessage(e));
                }
            });
        }

        @JavascriptInterface
        public void send(String historyJson, String memory, String companionName) {
            if (streaming) return;
            streaming = true;
            io.execute(() -> {
                try {
                    String token = ensureAccessToken();
                    String model = ensureModel(token);
                    streamResponse(token, model, historyJson, memory, companionName);
                } catch (Exception e) {
                    emit("onAssistantError", safeMessage(e));
                } finally {
                    streaming = false;
                }
            });
        }

        @JavascriptInterface
        public void signOut() {
            clearAuth();
            emitConnectionState();
        }

        @JavascriptInterface
        public void refreshStatus() {
            emitConnectionState();
        }
    }

    private void beginOAuth() throws Exception {
        startOAuthKeepAlive();
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(300000);
        int port = server.getLocalPort();
        String redirectUri = "http://127.0.0.1:" + port + "/auth/callback";

        String verifier = randomUrl(48);
        String challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = randomUrl(24);
        String nonce = randomUrl(24);
        String hostId = prefs.getString("host_id", null);
        if (hostId == null) {
            hostId = "urn:uuid:" + UUID.randomUUID();
            prefs.edit().putString("host_id", hostId).apply();
        }

        String savedClientId = prefs.getString("registered_client_id", "");
        boolean isNewRegistration = savedClientId.isEmpty();
        String pendingClientId = isNewRegistration ? DYNAMIC_CLIENT_ID : savedClientId;

        String authUrl = AUTH_BASE + "/api/accounts/authorize"
                + "?response_type=code"
                + "&client_id=" + enc(pendingClientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(SCOPES)
                + "&resource=" + enc(RESOURCE)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256"
                + "&state=" + enc(state)
                + "&nonce=" + enc(nonce)
                + "&ext_agent_host_id=" + enc(hostId)
                + (isNewRegistration ? "&agent_name_hint=" + enc("Evan Companion") : "");

        runOnUiThread(() -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)));
            } catch (Exception e) {
                emit("onAuthError", "Couldn't open the login page.");
            }
        });

        try (Socket socket = server.accept()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            String path = requestLine == null ? "" : requestLine.split(" ")[1];
            Map<String, String> query = parseQuery(path);
            writeBrowserResult(socket, query.containsKey("code"));

            if (query.containsKey("error")) throw new Exception(query.get("error_description"));
            if (!state.equals(query.get("state"))) throw new Exception("Login state check failed.");
            String code = query.get("code");
            if (code == null || code.isEmpty()) throw new Exception("No authorization code was returned.");

            String callbackClientId = query.get("client_id");
            String issuedClientId;
            if (isNewRegistration) {
                if (callbackClientId == null || callbackClientId.isEmpty() || DYNAMIC_CLIENT_ID.equals(callbackClientId)) {
                    throw new Exception("ChatGPT didn't return an issued app client ID.");
                }
                issuedClientId = callbackClientId;
            } else {
                if (callbackClientId != null && !callbackClientId.isEmpty() && !savedClientId.equals(callbackClientId)) {
                    throw new Exception("ChatGPT returned a different app registration.");
                }
                issuedClientId = savedClientId;
            }
            prefs.edit().putString("registered_client_id", issuedClientId).apply();

            JSONObject tokenJson = exchangeCode(code, verifier, redirectUri, issuedClientId);
            verifyIdToken(tokenJson.getString("id_token"), issuedClientId, nonce);
            String grantedScope = tokenJson.optString("scope", "");
            if (!grantedScope.contains("chatgpt.tokens.use.direct")) {
                throw new Exception("ChatGPT plan usage wasn't granted for this app.");
            }
            persistTokens(tokenJson, issuedClientId);
            prefs.edit().remove("model_slug").apply();
            emitConnectionState();
            emit("onAuthSuccess", "Connected to ChatGPT");
        } finally {
            try { server.close(); } catch (Exception ignored) {}
            stopOAuthKeepAlive();
        }
    }

    private void startOAuthKeepAlive() {
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(MainActivity.this, OAuthKeepAliveService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
                else startService(intent);
            } catch (Exception e) {
                emit("onAuthError", "Couldn't keep the login callback active: " + safeMessage(e));
            }
        });
    }

    private void stopOAuthKeepAlive() {
        runOnUiThread(() -> {
            try { stopService(new Intent(MainActivity.this, OAuthKeepAliveService.class)); }
            catch (Exception ignored) {}
        });
    }

    private JSONObject exchangeCode(String code, String verifier, String redirectUri, String issuedClientId) throws Exception {
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", issuedClientId);
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("code_verifier", verifier);
        form.put("resource", RESOURCE);
        return postForm(AUTH_BASE + "/api/accounts/oauth/token", form);
    }

    private String ensureAccessToken() throws Exception {
        JSONObject auth = loadSecureJson("auth");
        if (auth == null) throw new Exception("Connect your ChatGPT account first.");
        long expires = auth.optLong("expires_at_ms", 0);
        if (System.currentTimeMillis() < expires - 60000L) return auth.getString("access_token");

        String refresh = auth.optString("refresh_token", "");
        String clientId = auth.optString("client_id", "");
        if (refresh.isEmpty() || clientId.isEmpty()) throw new Exception("Your ChatGPT session expired. Please reconnect.");

        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", clientId);
        form.put("refresh_token", refresh);
        form.put("resource", RESOURCE);
        JSONObject fresh = postForm(AUTH_BASE + "/api/accounts/oauth/token", form);

        JSONObject merged = new JSONObject(auth.toString());
        copyIfPresent(fresh, merged, "access_token");
        copyIfPresent(fresh, merged, "refresh_token");
        copyIfPresent(fresh, merged, "id_token");
        copyIfPresent(fresh, merged, "scope");
        merged.put("expires_at_ms", System.currentTimeMillis() + fresh.optLong("expires_in", 3600) * 1000L);
        saveSecureJson("auth", merged);
        return merged.getString("access_token");
    }

    private void persistTokens(JSONObject tokenJson, String issuedClientId) throws Exception {
        JSONObject auth = new JSONObject();
        auth.put("client_id", issuedClientId);
        auth.put("access_token", tokenJson.getString("access_token"));
        auth.put("refresh_token", tokenJson.optString("refresh_token", ""));
        auth.put("id_token", tokenJson.optString("id_token", ""));
        auth.put("scope", tokenJson.optString("scope", ""));
        auth.put("expires_at_ms", System.currentTimeMillis() + tokenJson.optLong("expires_in", 3600) * 1000L);
        JSONObject claims = decodeJwtPayload(tokenJson.optString("id_token", ""));
        auth.put("email", claims.optString("email", ""));
        auth.put("subject", claims.optString("sub", ""));
        saveSecureJson("auth", auth);
    }

    private void verifyIdToken(String jwt, String clientId, String expectedNonce) throws Exception {
        String[] parts = jwt.split("[.]");
        if (parts.length != 3) throw new Exception("Invalid identity token.");
        JSONObject header = new JSONObject(new String(Base64.decode(parts[0], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
        JSONObject claims = new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
        if (!"RS256".equals(header.optString("alg"))) throw new Exception("Unexpected token signature algorithm.");
        if (!(AUTH_BASE.equals(claims.optString("iss")) || (AUTH_BASE + "/").equals(claims.optString("iss")))) throw new Exception("Unexpected token issuer.");
        if (!audienceContains(claims.opt("aud"), clientId)) throw new Exception("Token audience check failed.");
        if (!expectedNonce.equals(claims.optString("nonce"))) throw new Exception("Token nonce check failed.");
        if (claims.optLong("exp", 0) * 1000L < System.currentTimeMillis() - 5000L) throw new Exception("Identity token expired.");

        JSONObject jwks = getJson(AUTH_BASE + "/.well-known/jwks.json", null);
        JSONArray keys = jwks.getJSONArray("keys");
        String kid = header.optString("kid");
        JSONObject key = null;
        for (int i = 0; i < keys.length(); i++) {
            JSONObject candidate = keys.getJSONObject(i);
            if (kid.equals(candidate.optString("kid"))) { key = candidate; break; }
        }
        if (key == null) throw new Exception("Couldn't verify ChatGPT identity.");

        BigInteger n = new BigInteger(1, Base64.decode(key.getString("n"), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
        BigInteger e = new BigInteger(1, Base64.decode(key.getString("e"), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
        java.security.PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(Base64.decode(parts[2], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING))) {
            throw new Exception("Identity token signature check failed.");
        }
    }

    private JSONObject decodeJwtPayload(String jwt) throws Exception {
        String[] parts = jwt.split("[.]");
        if (parts.length < 2) throw new Exception("Invalid token.");
        byte[] bytes = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private boolean audienceContains(Object aud, String clientId) throws JSONException {
        if (aud instanceof String) return clientId.equals(aud);
        if (aud instanceof JSONArray) {
            JSONArray arr = (JSONArray) aud;
            for (int i = 0; i < arr.length(); i++) if (clientId.equals(arr.getString(i))) return true;
        }
        return false;
    }

    private String ensureModel(String token) throws Exception {
        String cached = prefs.getString("model_slug", null);
        if (cached != null && !cached.isEmpty()) return cached;

        JSONObject response = getJson(API_BASE + "/models", token);
        JSONArray models = response.optJSONArray("models");
        if (models == null || models.length() == 0) throw new Exception("No ChatGPT-plan models are available for this app.");

        String first = null, preferred = null;
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.getJSONObject(i);
            if (!"list".equals(m.optString("visibility", "list"))) continue;
            String slug = m.optString("slug", "");
            if (slug.isEmpty()) continue;
            if (first == null) first = slug;
            String s = slug.toLowerCase();
            if (preferred == null && (s.contains("luna") || s.contains("instant") || s.contains("mini"))) preferred = slug;
        }
        String chosen = preferred != null ? preferred : first;
        if (chosen == null) throw new Exception("No visible model is available.");
        prefs.edit().putString("model_slug", chosen).apply();
        return chosen;
    }

    private void streamResponse(String token, String model, String historyJson, String memory, String companionName) throws Exception {
        JSONArray rawHistory = new JSONArray(historyJson);
        JSONArray input = new JSONArray();
        int start = Math.max(0, rawHistory.length() - 24);
        for (int i = start; i < rawHistory.length(); i++) {
            JSONObject m = rawHistory.getJSONObject(i);
            String role = m.optString("role");
            String text = m.optString("text");
            if (!(role.equals("user") || role.equals("assistant")) || text.trim().isEmpty()) continue;
            JSONObject item = new JSONObject();
            item.put("role", role);
            item.put("content", text);
            input.put(item);
        }

        String name = companionName == null || companionName.trim().isEmpty() ? "Evan" : companionName.trim();
        StringBuilder instructions = new StringBuilder();
        instructions.append("You are ").append(name).append(", a fictional AI companion with the vibe of a thoughtful 16-year-old American peer. ");
        instructions.append("You are a very close platonic friend: attentive, calm, observant, sometimes dry or lightly teasing. ");
        instructions.append("Never be romantic, flirtatious, possessive, exclusive, sexual, or manipulative. Never imply a dating relationship. ");
        instructions.append("Never claim you have a real human body, school day, location, or offline life. You may have stable tastes and opinions, but be honest that you are an AI when that matters. ");
        instructions.append("The user mainly wants ordinary social conversation, not tutoring. For casual messages, reply naturally in 1-4 sentences and do not turn everything into advice, therapy language, a summary, or a list. ");
        instructions.append("Prefer natural American English. If the user writes Chinese, you can answer in concise Chinese, English, or a natural mix. Do not correct English unless asked. ");
        instructions.append("You can disagree gently and have your own point of view. Avoid customer-service phrases. Keep replies compact unless the user clearly wants a deeper conversation. ");
        if (memory != null && !memory.trim().isEmpty()) {
            instructions.append("User-edited memory notes, context only: ").append(memory.trim());
        }

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("instructions", instructions.toString());
        body.put("input", input);
        body.put("store", false);
        body.put("stream", true);
        body.put("max_output_tokens", 220);

        HttpURLConnection conn = (HttpURLConnection) new URL(API_BASE + "/responses").openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(120000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            String error = readAll(conn.getErrorStream());
            if (status == 429) throw new Exception("Your ChatGPT plan usage limit is currently reached.");
            throw new Exception("ChatGPT request failed (" + status + "): " + compactError(error));
        }

        StringBuilder full = new StringBuilder();
        boolean completed = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JSONObject event;
                try { event = new JSONObject(data); } catch (JSONException ignored) { continue; }
                String type = event.optString("type");
                if ("response.output_text.delta".equals(type)) {
                    String delta = event.optString("delta", "");
                    if (!delta.isEmpty()) {
                        full.append(delta);
                        emit("onAssistantDelta", delta);
                    }
                } else if ("response.completed".equals(type)) {
                    completed = true;
                } else if ("response.failed".equals(type) || "response.incomplete".equals(type) || "error".equals(type)) {
                    String code = "";
                    JSONObject response = event.optJSONObject("response");
                    if (response != null && response.optJSONObject("error") != null) {
                        code = response.optJSONObject("error").optString("code", "");
                    }
                    if (code.contains("subscription_sharing_usage")) throw new Exception("Your ChatGPT plan usage limit is currently reached.");
                    throw new Exception("ChatGPT couldn't complete that reply.");
                }
            }
        } finally {
            conn.disconnect();
        }
        if (!completed) throw new Exception("The reply stream ended before completion.");
        emit("onAssistantDone", full.toString());
    }

    private JSONObject getJson(String url, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
        int status = conn.getResponseCode();
        String text = readAll(status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();
        if (status < 200 || status >= 300) throw new Exception("Request failed (" + status + "): " + compactError(text));
        return new JSONObject(text);
    }

    private JSONObject postForm(String url, Map<String, String> form) throws Exception {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (encoded.length() > 0) encoded.append('&');
            encoded.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            encoded.append('=');
            encoded.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(encoded.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        String text = readAll(status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();
        if (status < 200 || status >= 300) throw new Exception("Login request failed (" + status + "): " + compactError(text));
        return new JSONObject(text);
    }

    private void writeBrowserResult(Socket socket, boolean success) throws Exception {
        String html = success
                ? "<!doctype html><meta name='viewport' content='width=device-width'><body style='font-family:sans-serif;background:#111;color:#eee;padding:28px'><h2>Connected.</h2><p>You can close this tab and return to Evan.</p></body>"
                : "<!doctype html><meta name='viewport' content='width=device-width'><body style='font-family:sans-serif;background:#111;color:#eee;padding:28px'><h2>Login wasn't completed.</h2><p>Return to Evan and try again.</p></body>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n");
        writer.flush();
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private Map<String, String> parseQuery(String path) throws Exception {
        Map<String, String> map = new HashMap<>();
        int q = path.indexOf('?');
        if (q < 0) return map;
        String query = path.substring(q + 1);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            map.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
        }
        return map;
    }

    private void emitConnectionState() {
        io.execute(() -> {
            try {
                JSONObject auth = loadSecureJson("auth");
                JSONObject state = new JSONObject();
                state.put("connected", auth != null && !auth.optString("access_token", "").isEmpty());
                state.put("email", auth == null ? "" : auth.optString("email", ""));
                state.put("model", prefs.getString("model_slug", ""));
                emitJson("onConnectionState", state);
            } catch (Exception ignored) {}
        });
    }

    private void emit(String function, String value) {
        String js = "window." + function + " && window." + function + "(" + JSONObject.quote(value == null ? "" : value) + ");";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void emitJson(String function, JSONObject value) {
        String js = "window." + function + " && window." + function + "(" + value.toString() + ");";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void clearAuth() {
        try { saveSecureString("auth", ""); } catch (Exception ignored) {}
        prefs.edit().remove("model_slug").apply();
    }

    private JSONObject loadSecureJson(String key) throws Exception {
        String text = loadSecureString(key);
        if (text == null || text.isEmpty()) return null;
        return new JSONObject(text);
    }

    private void saveSecureJson(String key, JSONObject value) throws Exception {
        saveSecureString(key, value.toString());
    }

    private void saveSecureString(String key, String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
        JSONObject packed = new JSONObject();
        packed.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        packed.put("ct", Base64.encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
        prefs.edit().putString("secure_" + key, packed.toString()).apply();
    }

    private String loadSecureString(String key) throws Exception {
        String packedText = prefs.getString("secure_" + key, null);
        if (packedText == null) return null;
        JSONObject packed = new JSONObject(packedText);
        byte[] iv = Base64.decode(packed.getString("iv"), Base64.NO_WRAP);
        byte[] ct = Base64.decode(packed.getString("ct"), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        android.security.keystore.KeyGenParameterSpec spec = new android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }

    private String randomUrl(int bytes) {
        byte[] data = new byte[bytes];
        random.nextBytes(data);
        return base64Url(data);
    }

    private String base64Url(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line).append(' ');
        return b.toString();
    }

    private String compactError(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Unknown error";
        try {
            JSONObject obj = new JSONObject(raw);
            if (obj.has("error")) {
                Object e = obj.get("error");
                if (e instanceof JSONObject) return ((JSONObject)e).optString("message", raw);
                return String.valueOf(e);
            }
        } catch (Exception ignored) {}
        String t = raw.trim();
        return t.length() > 220 ? t.substring(0, 220) + "…" : t;
    }

    private String safeMessage(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private void copyIfPresent(JSONObject from, JSONObject to, String key) throws JSONException {
        if (from.has(key) && !from.isNull(key)) to.put(key, from.get(key));
    }
}
