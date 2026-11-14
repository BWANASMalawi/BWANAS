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
        if (store.hasUnsubmittedFiscalSales()) throw new IllegalStateException("Sync the previous MRA sale first.");
        if ("true".equals(store.state("mra_blocked"))) {
            String message = blockMessage();
            if (!message.isEmpty()) throw new IllegalStateException(message);
        }
        MraActivationClient.checkConnection(context);
        long now = SystemClock.elapsedRealtime();
        if (configuration == null || now - configuredAt >= CONFIGURATION_CACHE_MS
                || "true".equals(store.state("mra_refresh_required"))) {
            loadConfiguration();
        }
        return configuration.prepare(lines, timestamp, soldAt);
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
        configuration = new MraInvoice(config, catalogue.getJSONArray("data"), activation);
        configuredAt = SystemClock.elapsedRealtime();
        store.saveState("mra_refresh_required", "");
    }

    public void confirm(String operationId, LocalStore.FiscalSale sale) throws Exception {
        if ("submitted".equals(sale.state)) return;
        if (!SecureStore.hasMraConfiguration(context)) throw new IOException("MRA credentials are unavailable.");
        checkBinding();
        JSONObject invoice = new JSONObject(sale.payload);
        String number = invoice.getJSONObject("invoiceHeader").getString("invoiceNumber");
        String token = MraActivationClient.token(context);
        if ("ready".equals(sale.state)) {
            // Any interruption after this transition is treated as an uncertain submission.
            store.updateFiscalSale(operationId, "ready", "sending", "");
            JSONObject response = MraApi.post("sales/submit-sales-transaction", invoice, token, null);
            JSONObject data = response.optJSONObject("data");
            if (data != null && data.optBoolean("shouldDownloadLatestConfig")) {
                store.saveState("mra_refresh_required", "true");
            }
            if (data != null && data.optBoolean("shouldBlockTerminal")) store.saveState("mra_blocked", "true");
            if (!MraApi.successful(response) || MraApi.validationUrl(data).isEmpty()
                    || !MraApi.noErrors(data, "validationErrors")) {
                store.updateFiscalSale(operationId, "sending", "sending", response.toString());
                throw new IOException("MRA did not confirm invoice " + number + ". Sync to check its status.");
            }
            store.updateFiscalSale(operationId, "sending", "submitted", data.toString());
        } else if ("sending".equals(sale.state)) {
            JSONObject response = MraApi.post("sales/get-invoice-by-number",
                    new JSONObject().put("invoiceNumber", number), token, null);
            JSONObject data = response.optJSONObject("data");
            JSONObject header = data == null ? null : data.optJSONObject("invoiceHeader");
            if (!MraApi.successful(response) || MraApi.validationUrl(data).isEmpty() || header == null
                    || !matchesInvoice(invoice, data)) {
                throw new IOException("MRA has not confirmed invoice " + number + ". Ask an administrator to review it.");
            }
            // The submit response may have been lost, including any configuration refresh flag.
            store.saveState("mra_refresh_required", "true");
            store.saveState("mra_blocked", "true");
            store.updateFiscalSale(operationId, "sending", "submitted", data.toString());
        } else {
            throw new IOException("This saved fiscal sale requires administrator review.");
        }
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
        if (sale == null || !"submitted".equals(sale.state)) return "";
        try { return MraApi.validationUrl(new JSONObject(sale.response)); }
        catch (org.json.JSONException error) { return ""; }
    }
}
