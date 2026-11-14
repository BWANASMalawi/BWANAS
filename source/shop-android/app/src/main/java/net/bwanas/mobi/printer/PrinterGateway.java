package net.bwanas.mobi.printer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

/** Embedded Sagereal AIDL printer used by the Mobi MP3 Plus. */
public final class PrinterGateway {
    private static final String PACKAGE = "com.sagereal.printer";
    private static final String START_ACTION = "sagereal.intent.action.START_PRINTER_SERVICE_AIDL";
    private static final String DESCRIPTOR = "com.sagereal.printer.PrinterInterface";
    // Verified from the legacy Mobi PrinterInterface AIDL contract.
    private static final int TRANSACTION_FINISH = 2;
    private static final int TRANSACTION_TEXT = 4;
    // OOPos 1.48 sends its MRA QR BMP via this transaction, followed by image mode 2.
    private static final int TRANSACTION_BITMAP = 10;
    private static final int BITMAP_MODE = 2;
    private static final int NORMAL_TEXT = 0;

    public interface Callback {
        void completed(boolean printed, String message);
    }

    private PrinterGateway() { }

    public static boolean available(Context context) {
        try { context.getPackageManager().getPackageInfo(PACKAGE, 0); return true; }
        catch (PackageManager.NameNotFoundException error) { return false; }
    }

    public static boolean start(Context context) {
        if (!available(context)) return false;
        try { context.startService(printerIntent()); return true; }
        catch (Exception error) { return false; }
    }

    /** Sends a receipt to the embedded printer and leaves the saved receipt intact for reprint. */
    public static void print(Context context, String receipt, Callback callback) {
        print(context, receipt, null, false, callback);
    }

    public static void printFiscal(Context context, String receipt, String validationUrl, Callback callback) {
        if (validationUrl == null || validationUrl.isEmpty()) {
            throw new IllegalArgumentException("A fiscal receipt requires an MRA validation URL.");
        }
        print(context, receipt, validationUrl, true, callback);
    }

    public static void printSale(Context context, String receipt, Callback callback) {
        print(context, receipt, null, true, callback);
    }

    private static byte[] receiptLogo(Context context) {
        int width = 192;
        android.graphics.Bitmap source = android.graphics.BitmapFactory.decodeResource(
                context.getResources(), net.bwanas.mobi.R.drawable.bwanas_receipt_logo);
        int height = Math.round((float) source.getHeight() * width / source.getWidth());
        android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(source, width, height, true);
        com.google.zxing.common.BitMatrix logo = new com.google.zxing.common.BitMatrix(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = scaled.getPixel(x, y);
                if (android.graphics.Color.alpha(pixel) >= 128 && android.graphics.Color.red(pixel) < 128) logo.set(x, y);
            }
        }
        if (scaled != source) scaled.recycle();
        source.recycle();
        return SagerealBitmap.encode(logo);
    }

    private static void print(Context context, String receipt, String validationUrl, boolean branded, Callback callback) {
        Context appContext = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        if (!available(appContext)) {
            main.post(() -> callback.completed(false, "Embedded Sagereal printer service is not installed on this POS."));
            return;
        }
        if (receipt == null || receipt.trim().isEmpty()) {
            main.post(() -> callback.completed(false, "There is no printable receipt for this transaction."));
            return;
        }
        if (!start(appContext)) {
            main.post(() -> callback.completed(false, "The embedded printer service could not be started."));
            return;
        }

        ServiceConnection connection = new ServiceConnection() {
            private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();

            private void finish(boolean printed, String message) {
                if (!released.compareAndSet(false, true)) return;
                try { appContext.unbindService(this); } catch (Exception ignored) { }
                main.post(() -> callback.completed(printed, message));
            }

            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                new Thread(() -> {
                    try {
                        byte[] qr = validationUrl == null ? null
                                : SagerealBitmap.encode(FiscalReceiptPrinter.qrMatrix(validationUrl));
                        if (branded) sendBitmap(binder, receiptLogo(appContext));
                        String body = branded ? receipt.replaceFirst("^ *BWANAS\\r?\\n", "") : receipt;
                        // Legacy Mobi sends normal text in <= 32-character lines and
                        // invokes a() twice to finish and feed the receipt.
                        for (String line : body.replace("\r", "").split("\n", -1)) sendLine(binder, line);
                        if (qr != null) sendBitmap(binder, qr);
                        // Extra clearance below the footer, including saved receipt reprints.
                        sendLine(binder, "");
                        sendLine(binder, "");
                        finishReceipt(binder);
                        finishReceipt(binder);
                        finish(true, "Receipt sent to the embedded printer.");
                    } catch (RemoteException error) {
                        finish(false, "The printer service did not accept the receipt. The receipt remains available to reprint.");
                    } catch (Exception error) {
                        finish(false, "The printer could not complete the receipt. The receipt remains available to reprint.");
                    }
                }, "bwanas-receipt-print").start();
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                finish(false, "The embedded printer service disconnected before the receipt was printed.");
            }
        };

        try {
            if (!appContext.bindService(printerIntent(), connection, Context.BIND_AUTO_CREATE)) {
                main.post(() -> callback.completed(false, "The embedded printer service could not be connected."));
            }
        } catch (Exception error) {
            main.post(() -> callback.completed(false, "The embedded printer service could not be connected."));
        }
    }

    private static Intent printerIntent() {
        return new Intent(START_ACTION).setPackage(PACKAGE);
    }

    private static void sendLine(IBinder printer, String text) throws RemoteException {
        if (text.isEmpty()) {
            sendText(printer, "\n");
            return;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + 32, text.length());
            sendText(printer, text.substring(start, end) + "\n");
            start = end;
        }
    }

    private static void sendText(IBinder printer, String text) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(text);
            data.writeInt(NORMAL_TEXT);
            printer.transact(TRANSACTION_TEXT, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void sendBitmap(IBinder printer, byte[] bitmap) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeByteArray(bitmap);
            data.writeInt(BITMAP_MODE);
            if (!printer.transact(TRANSACTION_BITMAP, data, reply, 0)) {
                throw new RemoteException("The Sagereal service did not accept the bitmap command.");
            }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void finishReceipt(IBinder printer) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            printer.transact(TRANSACTION_FINISH, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
