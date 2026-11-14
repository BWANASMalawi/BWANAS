package net.bwanas.mobi.mra;

import android.content.Context;
import android.os.SystemClock;

import net.bwanas.mobi.security.SecureStore;
import net.bwanas.mobi.storage.LocalStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/** Activated terminal cash sales and recovery by immutable invoice number. */
public final class MraSales {
    private static final long CONFIGURATION_CACHE_MS = 5 * 60 * 1000;
    private final Context context;
    private final LocalStore store;
    private MraInvoice configuration;
    private long configuredAt;

    public MraSales(Context context, LocalStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
    }

    public void checkBinding() {
        if (!store.state("mra_bound_device").equals(store.state("device_id")) || store.state("device_id").isEmpty()) {
            throw new IllegalStateException("The MRA terminal is linked to a different POS. Ask an administrator.");
        }
    }

    public JSONObject prepare(JSONArray lines, String timestamp, long soldAt) throws Exception {
        checkBinding();
        if (store.hasUnresolvedOnlineFiscalSale()) throw new IllegalStateException("Sync the previous MRA sale first.");
        if ("true".equals(store.state("mra_blocked"))) {
            String message = blockMessage();
            if (!message.isEmpty()) throw new IllegalStateException(message);
        }
        try {
            MraActivationClient.checkConnection(context);
            long now = SystemClock.elapsedRealtime();
            if (configuration == null || now - configuredAt >= CONFIGURATION_CACHE_MS
                    || "true".equals(store.state("mra_refresh_required"))) {
                loadConfiguration();
            }
        } catch (java.net.UnknownHostException | java.net.ConnectException |
                 java.net.NoRouteToHostException | java.net.SocketTimeoutException error) {
            return prepareOffline(lines, timestamp, soldAt);
        } catch (MraApi.HttpStatus error) {
            if (error.code != 502 && error.code != 503 && error.code != 504) throw error;
            return prepareOffline(lines, timestamp, soldAt);
        }
        if (store.hasUnsubmittedFiscalSales()) {
            throw new IllegalStateException("Sync the saved MRA sales before starting a new sale.");
        }
        return configuration.prepare(lines, timestamp, soldAt);
    }

    private JSONObject prepareOffline(JSONArray lines, String timestamp, long soldAt) throws Exception {
        String saved = SecureStore.mraOfflineProfile(context);
        if (saved.isEmpty() || "true".equals(store.state("mra_refresh_required"))) {
            throw new IllegalStateException("Connect to MRA first to download offline checkout settings.");
        }
        JSONObject profile = new JSONObject(saved);
        JSONObject activation = MraActivationClient.activation(context).getJSONObject("activatedTerminal");
        if (!activation.getString("terminalId").equals(profile.getString("terminalId"))) {
            throw new IllegalStateException("Connect to MRA to refresh this terminal's settings.");
        }
        JSONObject config = profile.getJSONObject("configuration");
        JSONObject limits = config.getJSONObject("terminalConfiguration").optJSONObject("offlineLimit");
        if (limits == null) throw new IllegalStateException("MRA has not enabled offline sales for this terminal.");
        return new MraInvoice(config, profile.getJSONArray("products"), activation).prepare(lines, timestamp, soldAt)
                .put("offlineLimits", limits).put("offlineProfileTime", profile.getLong("downloadedAt"))
                .put("offlineSaleTime", soldAt);
    }

    public void refreshAfterSync() throws Exception {
        if (!SecureStore.hasMraConfiguration(context)) return;
        checkBinding();
        if (configuration == null || "true".equals(store.state("mra_refresh_required"))) loadConfiguration();
    }

    private void loadConfiguration() throws Exception {
        String token = MraActivationClient.token(context);
        JSONObject envelope = MraApi.post("configuration/get-latest-configs", null, token, null);
        if (!MraApi.successful(envelope)) throw new IOException("MRA configuration is unavailable.");
        JSONObject config = envelope.getJSONObject("data");
        JSONObject terminal = config.getJSONObject("terminalConfiguration");
        if (!terminal.optBoolean("isActiveTerminal")) throw new IllegalStateException("MRA terminal is inactive.");
        JSONObject scope = new JSONObject()
                .put("tin", config.getJSONObject("taxpayerConfiguration").getString("tin"))
                .put("siteId", terminal.getJSONObject("terminalSite").getString("siteId"));
        JSONObject catalogue = MraApi.post("utilities/get-terminal-site-products", scope, token, null);
        if (!MraApi.successful(catalogue)) throw new IOException("MRA site products are unavailable.");
        JSONObject activation = MraActivationClient.activation(context).getJSONObject("activatedTerminal");
        JSONArray products = catalogue.getJSONArray("data");
        SecureStore.saveMraOfflineProfile(context, new JSONObject()
                .put("terminalId", activation.getString("terminalId")).put("downloadedAt", System.currentTimeMillis())
                .put("configuration", config).put("products", products).toString());
        configuration = new MraInvoice(config, products, activation);
        configuredAt = SystemClock.elapsedRealtime();
        store.saveState("mra_refresh_required", "");
    }

    public void confirm(String operationId, LocalStore.FiscalSale sale) throws Exception {
        if ("submitted".equals(sale.state)) return;
        if (!SecureStore.hasMraConfiguration(context)) throw new IOException("MRA credentials are unavailable.");
        checkBinding();
        JSONObject invoice = new JSONObject(sale.payload);
        invoice.remove("offlineValidationUrl");
        String number = invoice.getJSONObject("invoiceHeader").getString("invoiceNumber");
        String token = MraActivationClient.token(context);
        boolean offline = invoice.getJSONObject("invoiceSummary").has("offlineSignature");
        String sendingState = offline ? "offline_sending" : "sending";
        String retryState = offline ? "offline_ready" : "ready";
        boolean maySubmit = retryState.equals(sale.state);
        if (offline && sendingState.equals(sale.state)) {
            try {
                recover(operationId, invoice, number, token, sendingState);
                return;
            } catch (MraApi.HttpStatus error) {
                if (error.code != 404) throw error;
                maySubmit = true;
            }
        }
        if (maySubmit) {
            // Save the attempted state before network I/O. Recovery always looks up the original invoice number.
            store.updateFiscalSale(operationId, sale.state, sendingState, "");
            JSONObject response = MraApi.post("sales/submit-sales-transaction", invoice, token, null);
            JSONObject data = response.optJSONObject("data");
            if (data != null && data.optBoolean("shouldDownloadLatestConfig")) {
                store.saveState("mra_refresh_required", "true");
            }
            if (data != null && data.optBoolean("shouldBlockTerminal")) store.saveState("mra_blocked", "true");
            if (!MraApi.successful(response) || MraApi.validationUrl(data).isEmpty()
                    || !MraApi.noErrors(data, "validationErrors")) {
                store.updateFiscalSale(operationId, sendingState, sendingState, response.toString());
                throw new IOException("MRA did not confirm invoice " + number + ". Sync to check its status.");
            }
            store.updateFiscalSale(operationId, sendingState, "submitted", data.toString());
        } else if (sendingState.equals(sale.state)) {
            recover(operationId, invoice, number, token, sendingState);
        } else {
            throw new IOException("This saved fiscal sale requires administrator review.");
        }
    }

    private void recover(String operationId, JSONObject invoice, String number, String token, String sendingState) throws Exception {
        JSONObject response = MraApi.post("sales/get-invoice-by-number",
                new JSONObject().put("invoiceNumber", number), token, null);
        JSONObject data = response.optJSONObject("data");
        if (!MraApi.successful(response) || MraApi.validationUrl(data).isEmpty() || !matchesInvoice(invoice, data)) {
            throw new IOException("MRA has not confirmed invoice " + number + ". Ask an administrator to review it.");
        }
        // The original response may have included configuration or terminal block flags.
        store.saveState("mra_refresh_required", "true");
        store.saveState("mra_blocked", "true");
        store.updateFiscalSale(operationId, sendingState, "submitted", data.toString());
    }

    static boolean matchesInvoice(JSONObject saved, JSONObject found) throws Exception {
        JSONObject expected = saved.getJSONObject("invoiceHeader");
        JSONObject actual = found.getJSONObject("invoiceHeader");
        for (String field : new String[]{"invoiceNumber", "siteId", "sellerTIN"}) {
            if (!expected.getString(field).equals(actual.optString(field))) return false;
        }
        String savedDate = expected.getString("invoiceDateTime");
        String recoveredDate = actual.getString("invoiceDateTime");
        if (!sameTimestamp(savedDate, recoveredDate)) return false;
        JSONArray originalLines = saved.getJSONArray("invoiceLineItems");
        JSONArray foundLines = found.getJSONArray("invoiceLineItems");
        if (originalLines.length() != foundLines.length()) return false;
        for (int i = 0; i < originalLines.length(); i++) {
            JSONObject original = originalLines.getJSONObject(i);
            JSONObject returned = foundLines.getJSONObject(i);
            if (!original.getString("productCode").equals(returned.optString("productCode"))) return false;
            for (String field : new String[]{"quantity", "total", "totalVAT"}) {
                if (!sameAmount(original, returned, field)) return false;
            }
        }
        return sameAmount(saved.getJSONObject("invoiceSummary"), found.getJSONObject("invoiceSummary"), "invoiceTotal");
    }

    private static boolean sameAmount(JSONObject first, JSONObject second, String field) throws Exception {
        return new java.math.BigDecimal(first.get(field).toString()).compareTo(new java.math.BigDecimal(second.get(field).toString())) == 0;
    }

    private static boolean sameTimestamp(String expected, String actual) throws java.text.ParseException {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US);
        format.setLenient(false);
        String suffix = "(?:\\.[0-9]+)?(Z|[+-][0-9]{2}):?([0-9]{2})?$";
        String left = expected.replaceFirst(suffix, "$1$2").replace("Z", "+0000");
        String right = actual.replaceFirst(suffix, "$1$2").replace("Z", "+0000");
        if (!left.matches(".{19}[+-][0-9]{4}") || !right.matches(".{19}[+-][0-9]{4}")) return false;
        return format.parse(left).equals(format.parse(right));
    }

    private String blockMessage() throws Exception {
        JSONObject terminal = MraActivationClient.activation(context).getJSONObject("activatedTerminal");
        JSONObject response = MraApi.post("utilities/get-terminal-blocking-message",
                new JSONObject().put("terminalId", terminal.getString("terminalId")),
                MraActivationClient.token(context), null);
        JSONObject data = response.getJSONObject("data");
        if (!MraApi.successful(response) || !data.has("isBlocked")) throw new IOException("MRA terminal status is unavailable.");
        if (data.getBoolean("isBlocked")) {
            String reason = data.optString("blockingReason", "").trim();
            return reason.isEmpty() || "null".equals(reason) ? "MRA has blocked this terminal." : reason;
        }
        store.saveState("mra_blocked", "");
        return "";
    }

    public static JSONObject receiptDetails(LocalStore.FiscalSale sale) throws org.json.JSONException {
        JSONObject invoice = new JSONObject(sale.payload);
        return new JSONObject()
                .put("invoice_number", invoice.getJSONObject("invoiceHeader").getString("invoiceNumber"))
                .put("seller_tin", invoice.getJSONObject("invoiceHeader").getString("sellerTIN"))
                .put("total_vat", invoice.getJSONObject("invoiceSummary").get("totalVAT"))
                .put("validation_url", validationUrl(sale));
    }

    public static String validationUrl(LocalStore.FiscalSale sale) {
        if (sale == null) return "";
        try {
            String offline = new JSONObject(sale.payload).optString("offlineValidationUrl");
            if (!offline.isEmpty()) return offline;
            return "submitted".equals(sale.state) ? MraApi.validationUrl(new JSONObject(sale.response)) : "";
        } catch (org.json.JSONException error) { return ""; }
    }
}
