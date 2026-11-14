package net.bwanas.mobi.printer;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/** Renders the same validation URL for the screen and the built-in printer. */
public final class FiscalReceiptPrinter {
    private FiscalReceiptPrinter() { }

    public static Bitmap qrCode(String url) throws WriterException {
        BitMatrix code = qrMatrix(url);
        int size = code.getWidth();
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) pixels[y * size + x] = code.get(x, y) ? Color.BLACK : Color.WHITE;
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }

    static BitMatrix qrMatrix(String url) throws WriterException {
        return new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 240, 240);
    }
}
