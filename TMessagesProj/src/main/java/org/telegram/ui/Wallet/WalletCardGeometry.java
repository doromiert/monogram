package org.telegram.ui.Wallet;

import android.graphics.Path;

/** Face bounds and corner radius from the card_*.binobj meshes, in model units. */
final class WalletCardGeometry {
    static final float HALF_WIDTH = 1.0f;
    static final float TOP = 0.618561f;
    static final float BOTTOM = -0.593561f;
    static final float HALF_THICKNESS = 0.0125f;
    private static final float CORNER_RADIUS = 0.15f;

    static float compactRadius(float ratio){return Math.min(CORNER_RADIUS,(TOP-BOTTOM)*ratio*.25f);}
    static float compactY(float y,float ratio){
        if(ratio==1f)return y;
        float center=(TOP+BOTTOM)*.5f,half=(TOP-BOTTOM)*.5f;
        float radius=compactRadius(ratio),inner=half-CORNER_RADIUS,dy=y-center;
        float distance=Math.abs(dy);
        float mapped=distance>inner ? half*ratio-radius+(distance-inner)*radius/CORNER_RADIUS
            : distance*(half*ratio-radius)/inner;
        return center*ratio+Math.copySign(mapped,dy);
    }
    static float compactZ(float z,float ratio){
        if(ratio==1f)return z;
        float radius=compactRadius(ratio),distance=Math.abs(z),inner=HALF_WIDTH-CORNER_RADIUS;
        return Math.copySign(distance>inner ? HALF_WIDTH-radius+(distance-inner)*radius/CORNER_RADIUS
            : distance*(HALF_WIDTH-radius)/inner,z);
    }

    static float radiusX(float width) {
        return width * CORNER_RADIUS / (2.0f * HALF_WIDTH);
    }

    static float radiusY(float height) {
        return height * CORNER_RADIUS / (TOP - BOTTOM);
    }

    static void setFacePath(Path path, float width, float height) {
        path.rewind();
        // The layout's 1.64 aspect differs slightly from the mesh's aspect.
        // Separate radii recover circular model-space corners after projection.
        path.addRoundRect(0, 0, width, height, radiusX(width), radiusY(height), Path.Direction.CW);
    }
}
