package org.telegram.messenger;

import android.graphics.Bitmap;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.DecoderResult;
import com.google.zxing.common.DetectorResult;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.common.PerspectiveTransform;
import com.google.zxing.multi.qrcode.detector.MultiDetector;
import com.google.zxing.qrcode.decoder.Decoder;
import com.google.zxing.qrcode.decoder.QRCodeDecoderMetaData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Local QR decoding shared by camera and media previews; no downloaded detector model. */
public final class QrCodeScanner {
    private QrCodeScanner() {}

    public static final class Scan {
        public final String text;
        /** Four outer corners in source pixels, ordered top-left, top-right, bottom-right, bottom-left. */
        public final float[] corners;
        private Scan(String text, float[] corners) { this.text = text; this.corners = corners; }
    }

    public static List<Scan> scan(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return Collections.emptyList();
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        if ((long) w * h > 16 * 1024 * 1024) return Collections.emptyList();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        return scan(new RGBLuminanceSource(w, h, pixels));
    }

    public static List<Scan> scan(LuminanceSource source) {
        Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        List<Scan> scans = new ArrayList<>();
        Decoder decoder = new Decoder();
        for (int pass = 0; pass < 2; pass++) {
            try {
                BinaryBitmap image = new BinaryBitmap(new HybridBinarizer(pass == 0 ? source : source.invert()));
                for (DetectorResult found : new MultiDetector(image.getBlackMatrix()).detectMulti(hints)) {
                    try {
                        DecoderResult decoded = decoder.decode(found.getBits(), hints);
                        ResultPoint[] points = found.getPoints();
                        if (decoded.getOther() instanceof QRCodeDecoderMetaData)
                            ((QRCodeDecoderMetaData) decoded.getOther()).applyMirroredCorrection(points);
                        float[] corners = corners(points, found.getBits().getWidth());
                        if (corners != null && decoded.getText() != null && !decoded.getText().isEmpty())
                            scans.add(new Scan(decoded.getText(), corners));
                    } catch (ReaderException unreadable) { /* Other codes in the same frame may be readable. */ }
                }
            } catch (ReaderException absent) { /* Retry with inverted luminance. */ }
            if (!scans.isEmpty()) break;
        }
        return scans;
    }

    private static float[] corners(ResultPoint[] p, int dimension) {
        if (p == null || p.length < 3) return null;
        float far = dimension - 3.5f;
        float brX = p[2].getX() - p[1].getX() + p[0].getX();
        float brY = p[2].getY() - p[1].getY() + p[0].getY();
        float brModule = far;
        if (p.length > 3) { brX = p[3].getX(); brY = p[3].getY(); brModule = dimension - 6.5f; }
        PerspectiveTransform transform = PerspectiveTransform.quadrilateralToQuadrilateral(
                3.5f, 3.5f, far, 3.5f, brModule, brModule, 3.5f, far,
                p[1].getX(), p[1].getY(), p[2].getX(), p[2].getY(), brX, brY, p[0].getX(), p[0].getY());
        float[] corners = {0, 0, dimension, 0, dimension, dimension, 0, dimension};
        transform.transformPoints(corners);
        for (float coordinate : corners) if (Float.isNaN(coordinate) || Float.isInfinite(coordinate)) return null;
        return corners;
    }
}
