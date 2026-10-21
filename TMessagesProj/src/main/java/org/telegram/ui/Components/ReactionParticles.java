// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import com.google.zxing.common.detector.MathUtils;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BatchParticlesDrawHelper;
import java.util.ArrayList;

public class ReactionParticles {

    public static final int TYPE_RIGHT = 0;
    public static final int TYPE_RADIAL = 1;
    public static final int TYPE_RADIAL_INSIDE = 2;

    public final int type;
    public final ArrayList<Particle> particles;
    public final RectF bounds = new RectF();

    public final Bitmap b;
    private int bPaintColor;
    public final Paint bPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    public final Rect rect = new Rect();

    private float speed = 1f;
    private float lifetime = 1f;
    private int visibleCount;

    private boolean firstDraw = true;

    private @Nullable BatchParticlesDrawHelper.BatchParticlesBuffer batchParticlesBuffer;
    private final @Nullable Paint batchParticlesPaint;

    public ReactionParticles(int type, int n) {
        this.type = type;
        this.visibleCount = n;
        particles = new ArrayList<>(n);
        for (int i = 0; i < n; ++i) {
            particles.add(new Particle());
        }

        final int size = dp(10);
        final float k = .85f;
        b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Path path = new Path();
        int sizeHalf = size >> 1;
        int mid = (int) (sizeHalf * k);
        path.moveTo(0, sizeHalf);
        path.lineTo(mid, mid);
        path.lineTo(sizeHalf, 0);
        path.lineTo(size - mid, mid);
        path.lineTo(size, sizeHalf);
        path.lineTo(size - mid, size - mid);
        path.lineTo(sizeHalf, size);
        path.lineTo(mid, size - mid);
        path.lineTo(0, sizeHalf);
        path.close();
        Canvas canvas = new Canvas(b);
        Paint paint = new Paint();
        paint.setColor(Theme.multAlpha(Color.WHITE, .75f));
        canvas.drawPath(path, paint);

        if (BatchParticlesDrawHelper.isAvailable()) {
            batchParticlesBuffer = new BatchParticlesDrawHelper.BatchParticlesBuffer(n);
            batchParticlesBuffer.fillParticleTextureCords(0, 0, b.getWidth(), b.getHeight());
            batchParticlesPaint = BatchParticlesDrawHelper.createBatchParticlesPaint(b);
        } else {
            batchParticlesBuffer = null;
            batchParticlesPaint = null;
        }
    }

    public void setVisible(float x) {
        this.visibleCount = (int) (particles.size() * x);
    }

    public void setBounds(RectF bounds) {
        this.bounds.set(bounds);
        removeParticlesOutside();
    }

    public void setBounds(Rect bounds) {
        this.bounds.set(bounds);
        removeParticlesOutside();
    }

    public void setBounds(int l, int t, int r, int b) {
        this.bounds.set(l, t, r, b);
        removeParticlesOutside();
    }

    public void removeParticlesOutside() {
        if (type == TYPE_RADIAL_INSIDE) {
            final long now = System.currentTimeMillis();
            for (int i = 0; i < particles.size(); ++i) {
                final Particle p = particles.get(i);
                if (!bounds.contains((int) p.x, (int) p.y)) gen(p, now, firstDraw);
            }
        }
    }

    public void setLifetime(float lifetime) {
        this.lifetime = lifetime;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    private long lastInvalidateTime;
    private long lastTime;
    public boolean process() {
        if (!LiteMode.isEnabled(LiteMode.FLAG_PARTICLES)) {
            return false;
        }

        final long now = System.currentTimeMillis();
        final float deltaTime = Math.min(lastTime - now, 16) / 1000f * speed;
        for (int i = 0; i < Math.min(visibleCount, particles.size()); ++i) {
            final Particle p = particles.get(i);
            float lifetime = p.lifetime <= 0 ? 2f : (now - p.start) / (float) p.lifetime;
            if (lifetime > 1f) {
                gen(p, now, firstDraw);
                lifetime = 0f;
            }
            p.x += p.vx * deltaTime;
            p.y += p.vy * deltaTime;
            p.la = 4f * lifetime - 4f * lifetime * lifetime;
        }
        lastTime = now;

        if (lastInvalidateTime == 0 || lastInvalidateTime - now >= 66) {
            lastInvalidateTime = now;
            return true;
        }
        return false;
    }

    public void generateGrid() {
        ArrayList<PointF> points = poissonDiskSampling(dp(30), (int) bounds.width(), (int) bounds.height(), 15);

        for (int a = 0, N = points.size() - particles.size(); a < N; a++) {
            particles.add(new Particle());
        }
        visibleCount = points.size();

        if (batchParticlesBuffer != null) {
            batchParticlesBuffer = new BatchParticlesDrawHelper.BatchParticlesBuffer(visibleCount);
            batchParticlesBuffer.fillParticleTextureCords(0, 0, b.getWidth(), b.getHeight());
        }

        final long now = System.currentTimeMillis();
        for (int a = 0; a < visibleCount; a++) {
            final Particle p = particles.get(a);
            final PointF pF = points.get(a);

            gen(p, now, true);
            p.x = pF.x + bounds.left;
            p.y = pF.y + bounds.top;
            p.la = lerp(.4f, 1f, Utilities.fastRandom.nextFloat());
            p.s *= 1.25f;
        }
    }

    static boolean isValidPoint(PointF[][] grid, int width, int height, float cellsize,
                                int gwidth, int gheight,
                                PointF p, float radius) {
        /* Make sure the point is on the screen */
        final int gp = dp(15) / 2;
        if ((p.x < gp) || (p.x >= (width - gp)) || (p.y < gp) || (p.y >= (height - gp)))
            return false;

        /* Check neighboring eight cells */
        int xindex = (int)Math.floor(p.x / cellsize);
        int yindex = (int)Math.floor(p.y / cellsize);
        int i0 = Math.max(xindex - 1, 0);
        int i1 = Math.min(xindex + 1, gwidth - 1);
        int j0 = Math.max(yindex - 1, 0);
        int j1 = Math.min(yindex + 1, gheight - 1);

        for (int i = i0; i <= i1; i++)
            for (int j = j0; j <= j1; j++)
                if (grid[i][j] != null)
                    if (MathUtils.distance(grid[i][j].x, grid[i][j].y, p.x, p.y) < radius)
                        return false;

        /* If we get here, return true */
        return true;
    }

    static void insertPoint(PointF[][] grid, float cellsize, PointF point) {
        int xindex = (int)Math.floor(point.x / cellsize);
        int yindex = (int)Math.floor(point.y / cellsize);
        grid[xindex][yindex] = point;
    }


    private static ArrayList<PointF> poissonDiskSampling(float radius, int width, int height, int k) {
        int N = 2;
        /* The final set of points to return */
        ArrayList<PointF> points = new ArrayList<PointF>();
        /* The currently "active" set of points */
        ArrayList<PointF> active = new ArrayList<PointF>();
        /* Initial point p0 */
        PointF p0 = new PointF(
            lerp(0, width, Utilities.fastRandom.nextFloat()),
            lerp(0, height, Utilities.fastRandom.nextFloat())
        );
        PointF[][] grid;
        float cellsize = (float) Math.floor(radius/Math.sqrt(N));

        /* Figure out no. of cells in the grid for our canvas */
        int ncells_width = (int)Math.ceil(width/cellsize) + 1;
        int ncells_height = (int)Math.ceil(height/cellsize) + 1;

        /* Allocate the grid an initialize all elements to null */
        grid = new PointF[ncells_width][ncells_height];
        for (int i = 0; i < ncells_width; i++)
            for (int j = 0; j < ncells_height; j++)
                grid[i][j] = null;

        insertPoint(grid, cellsize, p0);
        points.add(p0);
        active.add(p0);

        while (!active.isEmpty()) {
            int random_index = active.size() > 1 ? Utilities.fastRandom.nextInt(active.size() - 1) : 0;
            PointF p = active.get(random_index);

            boolean found = false;
            for (int tries = 0; tries < k; tries++) {
                float theta = lerp(0, 360, Utilities.fastRandom.nextFloat());
                float new_radius = radius * lerp(1, 2, Utilities.fastRandom.nextFloat());
                float pnewx = (float) (p.x + new_radius * Math.cos(Math.toRadians(theta)));
                float pnewy = (float) (p.y + new_radius * Math.sin(Math.toRadians(theta)));
                PointF pnew = new PointF(pnewx, pnewy);

                if (!isValidPoint(grid, width, height, cellsize,
                        ncells_width, ncells_height,
                        pnew, radius))
                    continue;

                points.add(pnew);
                insertPoint(grid, cellsize, pnew);
                active.add(pnew);
                found = true;
                break;
            }

            /* If no point was found after k tries, remove p */
            if (!found)
                active.remove(random_index);
        }

        return points;
    }

    public void draw(Canvas canvas, int color) {
        draw(canvas, color, 1f);
    }

    public void draw(Canvas canvas, int color, float alpha) {
        if (!LiteMode.isEnabled(LiteMode.FLAG_PARTICLES)) {
            return;
        }

        final int particlesCount = Math.min(visibleCount, particles.size());
        final boolean useBatchRender = batchParticlesBuffer != null;
        if (useBatchRender) {
            final float bWidth = b.getWidth();
            final float bHeight = b.getHeight();
            for (int i = 0; i < particlesCount; ++i) {
                final Particle p = particles.get(i);
                final float pAlpha = p.a * p.s * alpha;
                final float halfWidth = bWidth / 2f * pAlpha;
                final float halfHeight = bHeight / 2f * pAlpha;
                batchParticlesBuffer.setParticleVertexCords(i, p.x - halfWidth, p.y - halfHeight, p.x + halfWidth, p.y + halfHeight);
                batchParticlesBuffer.setParticleColor(i, ColorUtils.setAlphaComponent(color, (int) (0xFF * Utilities.clamp01(p.la * alpha))));
            }
            BatchParticlesDrawHelper.draw(canvas, batchParticlesBuffer, particlesCount, batchParticlesPaint);
        } else {
            if (bPaintColor != color) {
                bPaint.setColorFilter(new PorterDuffColorFilter(bPaintColor = color, PorterDuff.Mode.SRC_IN));
            }

            for (int i = 0; i < particlesCount; ++i) {
                final Particle p = particles.get(i);
                p.draw(canvas, color, p.la * alpha);
            }
        }
        firstDraw = false;
    }

    public void gen(Particle p, final long now, boolean prefire) {
        p.start = now;
        p.lifetime = (long) (lerp(500, 2500, Utilities.fastRandom.nextFloat()) * lifetime);
        if (prefire) {
            p.start -= (long) (p.lifetime * Utilities.clamp01(Utilities.fastRandom.nextFloat()));
        }
        p.x = lerp(bounds.left, bounds.right, Utilities.fastRandom.nextFloat());
        p.y = lerp(bounds.top, bounds.bottom, Utilities.fastRandom.nextFloat());
        if (type == TYPE_RIGHT) {
            p.vx = dp(lerp(-7f, -18f, Utilities.fastRandom.nextFloat()));
            p.vy = dp(lerp(-2f, 2f, Utilities.fastRandom.nextFloat()));
        } else {
            p.vx = bounds.centerX() - p.x;
            p.vy = bounds.centerY() - p.y;
            final float d = dp(lerp(1f, 4f, Utilities.fastRandom.nextFloat())) / (float) Math.sqrt(p.vx * p.vx + p.vy * p.vy);
            p.vx *= d;
            p.vy *= d;
        }
        p.a = lerp(.4f, 1f, Utilities.fastRandom.nextFloat());
        p.s = .7f * lerp(.8f, 1.2f, Utilities.fastRandom.nextFloat());
    }

    public class Particle {
        public float x, y;
        public float vx, vy;
        public float s;
        public long start, lifetime;
        public float la, a;

        public void draw(Canvas canvas, int color, float alpha) {
            bPaint.setAlpha((int) (0xFF * alpha));
            rect.set(
                    (int) (x - b.getWidth() / 2f * a * s * alpha),
                    (int) (y - b.getHeight() / 2f * a * s * alpha),
                    (int) (x + b.getWidth() / 2f * a * s * alpha),
                    (int) (y + b.getHeight() / 2f * a * s * alpha)
            );
            canvas.drawBitmap(b, null, rect, bPaint);
        }
    }
}
