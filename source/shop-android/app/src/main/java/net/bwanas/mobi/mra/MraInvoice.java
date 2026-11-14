package net.bwanas.mobi.mra;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

/** Builds a cash invoice using approved product codes and VAT-inclusive till prices. */
public final class MraInvoice {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final String DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private final JSONObject configuration;
    private final JSONArray products;
    private final JSONObject terminalIdentity;

    MraInvoice(JSONObject configuration, JSONArray products, JSONObject terminalIdentity) {
        this.configuration = configuration;
        this.products = products;
        this.terminalIdentity = terminalIdentity;
    }

    public JSONObject prepare(JSONArray lines, String timestamp, long soldAt) throws Exception {
        if (lines.length() == 0) throw new IllegalArgumentException("A fiscal sale requires at least one item.");
        JSONObject global = configuration.getJSONObject("globalConfiguration");
        JSONObject terminal = configuration.getJSONObject("terminalConfiguration");
        JSONObject taxpayer = configuration.getJSONObject("taxpayerConfiguration");
        if (!terminal.optBoolean("isActiveTerminal")) throw new IllegalStateException("MRA terminal is inactive.");
        JSONObject site = terminal.getJSONObject("terminalSite");
        String siteId = site.getString("siteId");
        long taxpayerId = terminalIdentity.getLong("taxpayerId");
        long position = terminalIdentity.getLong("terminalPosition");
        if (siteId.isEmpty() || taxpayerId <= 0 || position < 0) {
            throw new IllegalStateException("MRA terminal numbering details are incomplete.");
        }

        JSONArray levies = taxpayer.optJSONArray("activatedLevies");
        for (int i = 0; levies != null && i < levies.length(); i++) {
            if (levies.getJSONObject(i).optBoolean("isActive")) {
                throw new IllegalStateException("This MRA terminal requires levy calculation. Ask an administrator.");
            }
        }

        Map<String, BigDecimal> taxRates = new LinkedHashMap<>();
        JSONArray configuredRates = global.getJSONArray("taxrates");
        for (int i = 0; i < configuredRates.length(); i++) {
            JSONObject rate = configuredRates.getJSONObject(i);
            taxRates.put(rate.getString("id"), new BigDecimal(rate.get("rate").toString()));
        }

        Map<String, JSONObject> catalogue = new LinkedHashMap<>();
        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.getJSONObject(i);
            if (siteId.equals(product.optString("siteId"))
                    && catalogue.put(product.getString("productCode"), product) != null) {
                throw new IllegalStateException("MRA has duplicate product codes for this site.");
            }
        }

        JSONArray items = new JSONArray();
        Map<String, BigDecimal[]> totalsByRate = new LinkedHashMap<>();
        BigDecimal totalVat = BigDecimal.ZERO;
        BigDecimal invoiceTotal = BigDecimal.ZERO;
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.getJSONObject(i);
            String code = line.getString("sku");
            JSONObject product = catalogue.get(code);
            if (product == null) throw new IllegalStateException(code + " is missing from the MRA site product list.");
            String taxRateId = product.getString("taxRateId");
            BigDecimal rate = taxRates.get(taxRateId);
            if (rate == null || rate.signum() < 0) throw new IllegalStateException("MRA tax rate is missing for " + code + ".");

            BigDecimal quantity = new BigDecimal(line.get("quantity").toString());
            BigDecimal grossUnit = new BigDecimal(line.get("unit_price").toString());
            if (quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 0 || grossUnit.signum() <= 0) {
                throw new IllegalStateException("MRA sales require positive prices and whole item quantities.");
            }
            BigDecimal gross = money(grossUnit.multiply(quantity));
            BigDecimal netUnit = grossUnit.multiply(HUNDRED).divide(HUNDRED.add(rate), 6, RoundingMode.HALF_UP);
            BigDecimal net = money(gross.multiply(HUNDRED).divide(HUNDRED.add(rate), 2, RoundingMode.HALF_UP));
            BigDecimal vat = gross.subtract(net);
            items.put(new JSONObject().put("productCode", code)
                    .put("description", product.optString("description", line.getString("description")))
                    .put("unitPrice", netUnit).put("quantity", quantity).put("discount", 0)
                    .put("total", net).put("totalVAT", vat).put("taxRateId", taxRateId)
                    .put("isProduct", product.getBoolean("isProduct")));
            BigDecimal[] totals = totalsByRate.get(taxRateId);
            if (totals == null) {
                totals = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
                totalsByRate.put(taxRateId, totals);
            }
            totals[0] = totals[0].add(net);
            totals[1] = totals[1].add(vat);
            invoiceTotal = invoiceTotal.add(gross);
            totalVat = totalVat.add(vat);
        }

        JSONArray breakdown = new JSONArray();
        for (Map.Entry<String, BigDecimal[]> group : totalsByRate.entrySet()) {
            breakdown.put(new JSONObject().put("rateId", group.getKey())
                    .put("taxableAmount", group.getValue()[0]).put("taxAmount", group.getValue()[1]));
        }

        JSONObject header = new JSONObject().put("invoiceDateTime", timestamp)
                .put("sellerTIN", taxpayer.getString("tin")).put("siteId", siteId)
                .put("globalConfigVersion", global.getInt("versionNo"))
                .put("taxpayerConfigVersion", taxpayer.getInt("versionNo"))
                .put("terminalConfigVersion", terminal.getInt("versionNo"))
                .put("isReliefSupply", false).put("paymentMethod", "Cash");
        JSONObject summary = new JSONObject().put("taxBreakDown", breakdown).put("levyBreakDown", new JSONArray())
                .put("totalVAT", totalVat).put("invoiceTotal", invoiceTotal).put("amountTendered", invoiceTotal);
        return new JSONObject().put("invoiceHeader", header).put("invoiceLineItems", items).put("invoiceSummary", summary)
                .put("numberPrefix", radix64(taxpayerId) + "-" + radix64(position) + "-" + radix64(julianDay(soldAt)));
    }

    public static String radix64(long value) {
        if (value < 0) throw new IllegalArgumentException("Invoice number parts must be non-negative.");
        StringBuilder digits = new StringBuilder();
        do {
            digits.append(DIGITS.charAt((int) (value % 64)));
            value /= 64;
        } while (value != 0);
        return digits.reverse().toString();
    }

    private static long julianDay(long timestamp) {
        Calendar local = Calendar.getInstance(TimeZone.getTimeZone("Africa/Blantyre"));
        local.setTimeInMillis(timestamp);
        int year = local.get(Calendar.YEAR);
        int month = local.get(Calendar.MONTH) + 1;
        int day = local.get(Calendar.DAY_OF_MONTH);
        int adjust = (14 - month) / 12;
        int adjustedYear = year + 4800 - adjust;
        int adjustedMonth = month + 12 * adjust - 3;
        return day + (153 * adjustedMonth + 2) / 5 + 365L * adjustedYear
                + adjustedYear / 4 - adjustedYear / 100 + adjustedYear / 400 - 32045;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
