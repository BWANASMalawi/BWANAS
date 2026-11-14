package net.bwanas.mobi.printer;

import com.google.zxing.common.BitMatrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** An uncompressed 384-dot-wide, 8-bit Windows BMP, matching the legacy receipt image format. */
final class SagerealBitmap {
    private static final int WIDTH = 384;
    private static final int PALETTE_SIZE = 256;
    private static final int PIXEL_OFFSET = 14 + 40 + PALETTE_SIZE * 4;

    private SagerealBitmap() { }

    static byte[] encode(BitMatrix qr) {
        if (qr.getWidth() > WIDTH) throw new IllegalArgumentException("QR exceeds printer width.");
        int height = qr.getHeight();
        int imageSize = WIDTH * height;
        ByteBuffer bmp = ByteBuffer.allocate(PIXEL_OFFSET + imageSize).order(ByteOrder.LITTLE_ENDIAN);
        bmp.put((byte) 'B').put((byte) 'M').putInt(bmp.capacity()).putInt(0).putInt(PIXEL_OFFSET);
        bmp.putInt(40).putInt(WIDTH).putInt(height).putShort((short) 1).putShort((short) 8);
        bmp.putInt(0).putInt(imageSize).putInt(11811).putInt(11811).putInt(PALETTE_SIZE).putInt(0);
        for (int i = 0; i < PALETTE_SIZE; i++) {
            byte grey = (byte) i;
            bmp.put(grey).put(grey).put(grey).put((byte) 0);
        }
        int left = (WIDTH - qr.getWidth()) / 2;
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < WIDTH; x++) {
                int column = x - left;
                boolean black = column >= 0 && column < qr.getWidth() && qr.get(column, y);
                bmp.put(black ? (byte) 0 : (byte) 255);
            }
        }
        return bmp.array();
    }
}
