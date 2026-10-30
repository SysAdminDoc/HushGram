/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Method;

/**
 * Every HushGram title in Instagram's gradient: every stop reads on the black bar, a sheen sweeps
 * as the title opens and once each period, the colours drift without a jump, a worn title is drawn
 * in more than one colour and in different ones as time passes, and it keeps moving on screen with
 * Android's animations off, as the S22 runs (2026-10-26).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 30, qualifiers = "w411dp-h891dp-xxhdpi")
public class ShimmerTitleTest {
    @Test
    public void everyGradientStopReadsOnTheBlackBar() {
        for (int color : ShimmerTitle.COLORS) {
            assertTrue(Integer.toHexString(color), contrast(color, ScreenColors.DEFAULT.background) >= 7.0);
        }
    }

    @Test
    public void aSweepRunsAsTheTitleOpensAndOnceEachPeriod() {
        assertEquals(0f, ShimmerTitle.sweep(0L), 0f);
        assertEquals(0.5f, ShimmerTitle.sweep(ShimmerTitle.SWEEP_MS / 2), 0.001f);
        assertEquals(-1f, ShimmerTitle.sweep(ShimmerTitle.SWEEP_MS), 0f);
        assertEquals(-1f, ShimmerTitle.sweep(ShimmerTitle.PERIOD_MS - 1), 0f);
        assertEquals(0f, ShimmerTitle.sweep(ShimmerTitle.PERIOD_MS), 0f);
        assertEquals(-1f, ShimmerTitle.sweep(-5L), 0f);
    }

    @Test
    public void theColoursDriftRoundAndStartAgainWhereTheyBegan() {
        assertEquals(0f, ShimmerTitle.drift(0L), 0f);
        assertEquals(0f, ShimmerTitle.drift(-5L), 0f);
        assertEquals(0.5f, ShimmerTitle.drift(ShimmerTitle.DRIFT_MS / 2), 0.001f);
        assertEquals(0f, ShimmerTitle.drift(ShimmerTitle.DRIFT_MS), 0f);
        float before = 0f;
        for (int step = 1; step < 20; step++) {
            float now = ShimmerTitle.drift(step * ShimmerTitle.DRIFT_MS / 20);
            assertTrue(now > before);
            before = now;
        }
    }

    @Test
    public void aWornTitleIsDrawnInTheGradientAndChangesAsItDrifts() {
        TextView title = title("Search settings");
        ShimmerTitle.wear(title);
        assertTrue(ShimmerTitle.wears(title));
        // A whole drift round on, between sweeps: yellow at the start, blue at the end.
        assertEquals(-1f, ShimmerTitle.sweep(ShimmerTitle.DRIFT_MS), 0f);
        Bitmap still = draw(title, ShimmerTitle.DRIFT_MS);
        assertTrue("warm letters", anyPixel(still, true));
        assertTrue("cool letters", anyPixel(still, false));
        Bitmap later = draw(title, ShimmerTitle.DRIFT_MS + ShimmerTitle.DRIFT_MS / 4);
        assertFalse("the colours moved", still.sameAs(later));
        Bitmap sweeping = draw(title, ShimmerTitle.SWEEP_MS / 2);
        assertTrue("the sheen pass still shows the gradient", anyPixel(sweeping, true));
    }

    @Test
    public void aTitleOnScreenKeepsMovingWithAnimationsOff() throws Exception {
        Method scale = ValueAnimator.class.getMethod("setDurationScale", float.class);
        scale.invoke(null, 0f);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            assertFalse(ValueAnimator.areAnimatorsEnabled());
            TextView title = title("HushGram");
            FrameLayout root = new FrameLayout(controller.get());
            root.addView(title);
            controller.get().setContentView(root);
            ShimmerTitle.wear(title);
            assertNotNull("the gradient is on as the title opens", shift(title, 100));
            // Past the opening sweep the paint holds the drifting gradient alone.
            Float first = shift(title, ShimmerTitle.SWEEP_MS + 200);
            Float later = shift(title, 500);
            assertNotNull(first);
            assertNotNull(later);
            assertTrue("the colours drifted with animations off: " + first + " then " + later, later > first);
        } finally {
            scale.invoke(null, 1f);
        }
    }

    @Test
    public void aWornTitleCanBeLetGo() {
        java.lang.ref.WeakReference<TextView> gone = wornAndDropped();
        for (int i = 0; i < 20 && gone.get() != null; i++) {
            System.gc();
            System.runFinalization();
        }
        assertNull("the gradient kept its title alive", gone.get());
    }

    private static java.lang.ref.WeakReference<TextView> wornAndDropped() {
        TextView title = title("HushGram");
        ShimmerTitle.wear(title);
        assertTrue(ShimmerTitle.wears(title));
        return new java.lang.ref.WeakReference<>(title);
    }

    /** Runs the main looper [ms] on and gives how far the title's shader is moved along x, if any. */
    private static Float shift(TextView title, long ms) {
        ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        Shader shader = title.getPaint().getShader();
        if (shader == null) return null;
        Matrix local = new Matrix();
        shader.getLocalMatrix(local);
        float[] values = new float[9];
        local.getValues(values);
        return values[Matrix.MTRANS_X];
    }

    private static TextView title(String text) {
        TextView title = new TextView(RuntimeEnvironment.getApplication());
        // As in the bar: a laid-out TextView reads its layout params when its text changes.
        title.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setText(text);
        return title;
    }

    private static Bitmap draw(TextView title, long sinceStart) {
        title.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        title.layout(0, 0, title.getMeasuredWidth(), title.getMeasuredHeight());
        ShimmerTitle.frameFor(title, sinceStart);
        Bitmap bitmap = Bitmap.createBitmap(title.getWidth(), title.getHeight(), Bitmap.Config.ARGB_8888);
        title.draw(new Canvas(bitmap));
        return bitmap;
    }

    /** Whether a solid letter pixel is clearly warm (red over blue) or, for not [warm], clearly cool. */
    private static boolean anyPixel(Bitmap bitmap, boolean warm) {
        for (int x = 0; x < bitmap.getWidth(); x++) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) < 250) continue;
                int lean = Color.red(pixel) - Color.blue(pixel);
                if (warm ? lean > 60 : lean < -60) return true;
            }
        }
        return false;
    }

    private static double contrast(int one, int other) {
        double a = Color.luminance(one) + 0.05, b = Color.luminance(other) + 0.05;
        return Math.max(a, b) / Math.min(a, b);
    }
}
