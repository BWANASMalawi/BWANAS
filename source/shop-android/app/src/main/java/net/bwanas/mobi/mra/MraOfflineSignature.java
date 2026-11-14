package net.bwanas.mobi.mra;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

/** Prints an MRA validation URL from the same numbered invoice the outbox will submit. */
public final class MraOfflineSignature {
    private MraOfflineSignature() { }

    public static void sign(JSONObject invoice, String julianDate, String secretKey) throws Exception {
        JSONObject summary = invoice.getJSONObject("invoiceSummary");
        String query = "TI=" + invoice.getJSONObject("invoiceHeader").getString("invoiceNumber")
                + "&N=" + invoice.getJSONArray("invoiceLineItems").length()
                + "&I=" + money(summary, "invoiceTotal") + "&V=" + money(summary, "totalVAT")
                + "&T=" + julianDate;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = android.util.Base64.encodeToString(mac.doFinal(query.getBytes(StandardCharsets.UTF_8)),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        summary.put("offlineSignature", signature);
        String urlQuery = query.replace("+", "%2B");
        invoice.put("offlineValidationUrl",
                "https://eis-portal.mra.mw/ReceiptValidation/Validate/?" + urlQuery + "&S=" + signature);
    }

    private static String money(JSONObject summary, String field) throws Exception {
        return new BigDecimal(summary.get(field).toString()).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
