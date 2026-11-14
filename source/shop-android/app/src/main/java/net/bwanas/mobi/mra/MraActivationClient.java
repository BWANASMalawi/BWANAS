package net.bwanas.mobi.mra;

import net.bwanas.mobi.BuildConfig;
import net.bwanas.mobi.security.SecureStore;

import android.content.Context;
import android.os.Build;
import android.util.Base64;
import android.provider.Settings;

import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;

import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Direct MRA onboarding. The owner assigns the TAC to this POS. The terminal sends it directly to MRA. */
public final class MraActivationClient {
    private MraActivationClient() { }

    public static String activate(Context context, String tac, String productId) throws Exception {
        if (!SecureStore.pendingMraActivation(context).isEmpty()) return resumeConfirmation(context);
        if (productId.trim().isEmpty()) throw new IllegalArgumentException("Enter the product ID issued by MRA.");
        String macAddress = stableDeviceMac(context);
        JSONObject platform = new JSONObject();
        platform.put("osName", "Android");
        platform.put("osVersion", Build.VERSION.RELEASE);
        platform.put("osBuild", Build.DISPLAY);
        platform.put("macAddress", macAddress);
        JSONObject pos = new JSONObject();
        pos.put("productID", productId);
        pos.put("productVersion", BuildConfig.VERSION_NAME);
        JSONObject environment = new JSONObject();
        environment.put("platform", platform);
        environment.put("pos", pos);
        JSONObject request = new JSONObject();
        request.put("terminalActivationCode", tac);
        request.put("environment", environment);

        JSONObject response = MraApi.post("onboarding/activate-terminal", request, null, null);
        JSONObject data = response.optJSONObject("data");
        if (!MraApi.successful(response) || data == null) throw new IllegalStateException(mraResponseMessage(response, "MRA did not return terminal credentials."));
        JSONObject activated = data.optJSONObject("activatedTerminal");
        if (activated == null) throw new IllegalStateException(mraResponseMessage(response, "MRA did not activate this terminal."));
        String terminalId = activated.optString("terminalId", "");
        JSONObject credentials = activated.optJSONObject("terminalCredentials");
        String secretKey = credentials == null ? "" : credentials.optString("secretKey", "");
        if (terminalId.isEmpty() || secretKey.isEmpty() || credentials.optString("jwtToken").isEmpty()) throw new IllegalStateException(mraResponseMessage(response, "MRA returned incomplete terminal credentials."));

        SecureStore.savePendingMraActivation(context, new JSONObject()
                .put("configuration", response.toString())
                .put("terminalId", terminalId)
                .put("signature", hmacSha512Base64(tac, secretKey)).toString());
        return resumeConfirmation(context);
    }

    public static String resumeConfirmation(Context context) throws Exception {
        JSONObject pending = new JSONObject(SecureStore.pendingMraActivation(context));
        String terminalId = pending.getString("terminalId");
        JSONObject terminal = new JSONObject(pending.getString("configuration"))
                .getJSONObject("data").getJSONObject("activatedTerminal");
        if (terminal.optLong("taxpayerId", 0) <= 0 || terminal.optLong("terminalPosition", -1) < 0) {
            throw new IllegalStateException("MRA did not supply the taxpayer ID and terminal position.");
        }
        JSONObject confirmation = new JSONObject().put("terminalId", terminalId);
        JSONObject result = MraApi.post("onboarding/terminal-activated-confirmation", confirmation,
                null, pending.getString("signature"));
        if (!MraApi.successful(result) || !result.optBoolean("data", false)) {
            throw new IllegalStateException(mraResponseMessage(result, "MRA confirmation is still pending."));
        }
        SecureStore.saveMraConfiguration(context, pending.getString("configuration"));
        SecureStore.clearPendingMraActivation(context);
        return terminalId;
    }

    private static String mraResponseMessage(JSONObject response, String fallback) {
        String[] fields = {"message", "detail", "remark", "error", "reason"};
        for (String field : fields) {
            Object value = response.opt(field);
            if (value instanceof String && !((String) value).trim().isEmpty()) return ((String) value).trim();
            if (value instanceof JSONObject) {
                JSONObject nested = (JSONObject) value;
                for (String nestedField : fields) {
                    String message = nested.optString(nestedField, "").trim();
                    if (!message.isEmpty()) return message;
                }
            }
        }
        return fallback;
    }

    /** Uses the POS network MAC where Android exposes it; otherwise a stable locally administered identifier. */
    private static String stableDeviceMac(Context context) throws Exception {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                byte[] address = interfaces.nextElement().getHardwareAddress();
                if (address == null || address.length != 6) continue;
                boolean allZero = true;
                for (byte value : address) if (value != 0) { allZero = false; break; }
                if (!allZero) return mac(address);
            }
        } catch (Exception ignored) { }
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((androidId == null ? "bwanas-mobi" : androidId).getBytes(StandardCharsets.UTF_8));
        digest[0] = (byte) ((digest[0] & 0xFE) | 0x02);
        byte[] six = new byte[6]; System.arraycopy(digest, 0, six, 0, 6);
        return mac(six);
    }

    private static String mac(byte[] value) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < value.length; i++) { if (i > 0) output.append('-'); output.append(String.format(java.util.Locale.US, "%02X", value[i] & 0xFF)); }
        return output.toString();
    }

    public static String checkConnection(Context context) throws Exception {
        JSONObject response = MraApi.post("utilities/ping", null, token(context), null);
        JSONObject data = response.optJSONObject("data");
        if (!MraApi.successful(response) || data == null || data.optString("serverDate").isEmpty()) {
            throw new IllegalStateException("MRA did not confirm the connection.");
        }
        return "MRA terminal connection is available.";
    }

    public static String offlineSigningKey(Context context) throws Exception {
        return activation(context).getJSONObject("activatedTerminal").getJSONObject("terminalCredentials")
                .getString("secretKey");
    }

    static JSONObject activation(Context context) throws Exception {
        return new JSONObject(SecureStore.mraConfiguration(context)).getJSONObject("data");
    }

    public static String terminalId(Context context) throws Exception {
        return activation(context).getJSONObject("activatedTerminal").getString("terminalId");
    }

    static String token(Context context) throws Exception {
        JSONObject stored = new JSONObject(SecureStore.mraConfiguration(context));
        JSONObject credentials = stored.getJSONObject("data").getJSONObject("activatedTerminal")
                .getJSONObject("terminalCredentials");
        String token = credentials.getString("jwtToken");
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalStateException("The MRA terminal token is invalid.");
        JSONObject claims = new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE), StandardCharsets.UTF_8));
        long expires = claims.optLong("exp", 0);
        long secondsUntilExpiry = expires - System.currentTimeMillis() / 1000L;
        if (expires > 0 && secondsUntilExpiry < 7 * 86400L) {
            JSONObject result = MraApi.post("configuration/request-new-terminal-token", null, token, null);
            Object newToken = result.opt("data");
            if (!MraApi.successful(result) || !(newToken instanceof String) || ((String) newToken).isEmpty()) {
                throw new IllegalStateException("MRA could not renew this terminal token.");
            }
            token = (String) newToken;
            credentials.put("jwtToken", token);
            SecureStore.saveMraConfiguration(context, stored.toString());
        }
        return token;
    }

    private static String hmacSha512Base64(String value, String secretKey) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return Base64.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
}
