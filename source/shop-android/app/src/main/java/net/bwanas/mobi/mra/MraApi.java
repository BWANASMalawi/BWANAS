package net.bwanas.mobi.mra;

import net.bwanas.mobi.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Transport shared by terminal activation, configuration and sales. */
final class MraApi {
    private MraApi() { }

    static final class HttpStatus extends IOException {
        final int code;
        HttpStatus(int code) {
            super("MRA returned HTTP " + code + ".");
            this.code = code;
        }
    }

    static JSONObject post(String path, JSONObject body, String token, String signature) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.MRA_BASE_URL + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Accept", "application/json");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (signature != null) connection.setRequestProperty("x-signature", signature);
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            catch (Exception error) {
                connection.disconnect();
                throw error;
            }
        }
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new HttpStatus(status);
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int size;
                while ((size = in.read(buffer)) != -1) {
                    if (result.size() + size > 8 * 1024 * 1024) throw new IOException("MRA response is too large.");
                    result.write(buffer, 0, size);
                }
                return new JSONObject(new String(result.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    static boolean successful(JSONObject response) {
        int code = response.optInt("statusCode", -1);
        return (code == 0 || code == 1) && noErrors(response, "errors");
    }

    static boolean noErrors(JSONObject response, String name) {
        if (response.isNull(name)) return true;
        JSONArray errors = response.optJSONArray(name);
        return errors != null && errors.length() == 0;
    }

    static String validationUrl(JSONObject data) {
        if (data == null) return "";
        String url = data.optString("validationURL");
        try {
            URI parsed = URI.create(url);
            String host = parsed.getHost();
            if ("https".equalsIgnoreCase(parsed.getScheme()) && host != null
                    && host.toLowerCase(java.util.Locale.ROOT).endsWith(".mra.mw")
                    && parsed.getUserInfo() == null && parsed.getPort() == -1) return url;
        } catch (IllegalArgumentException ignored) { }
        return "";
    }
}
