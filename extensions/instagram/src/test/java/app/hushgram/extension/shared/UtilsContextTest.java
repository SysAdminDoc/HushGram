/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.List;

/** The context every hook reads is the application's, whatever the start hook hands in. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class UtilsContextTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity originalActivity;

    @Before
    public void keepActivity() {
        originalActivity = Utils.getActivity();
    }

    @After
    public void restoreActivity() {
        Utils.setActivity(originalActivity);
    }

    /** A static field holding an activity keeps its window and views after it's closed or recreated. */
    @Test
    public void anActivityIsFollowedWeaklyAndItsApplicationIsKept() {
        Application app = RuntimeEnvironment.getApplication();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            assertSame(app, Utils.getContext());
            assertSame(activity, Utils.getActivity());
        }
    }

    /** Anything that isn't an activity is kept as handed in, so a wrapper's own answers still count. */
    @Test
    public void aWrapperThatIsNotAnActivityIsKeptAsItIs() {
        Context wrapper = new ContextWrapper(RuntimeEnvironment.getApplication());
        Utils.setContext(wrapper);
        assertSame(wrapper, Utils.getContext());
    }

    /** Instagram's start hook hands over the application, so the activity on screen is followed from it. */
    @Test
    public void theActivityOnScreenIsFollowedFromTheApplication() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        try (ActivityController<Activity> main = Robolectric.buildActivity(Activity.class).setup()) {
            assertSame(main.get(), Utils.getActivity());
            try (ActivityController<Activity> story = Robolectric.buildActivity(Activity.class).setup()) {
                assertSame("the one over it while it shows", story.get(), Utils.getActivity());
            }
            assertNull("never one that's destroyed", Utils.getActivity());
            main.pause().resume();
            assertSame("the one under it once it shows again", main.get(), Utils.getActivity());
        }
        assertNull(Utils.getActivity());
    }

    /** Every start hands the context over again, and each would otherwise add one more follower. */
    @Test
    public void anApplicationIsFollowedOnce() throws ReflectiveOperationException {
        Application app = RuntimeEnvironment.getApplication();
        Field callbacks = Application.class.getDeclaredField("mActivityLifecycleCallbacks");
        callbacks.setAccessible(true);
        int followers = ((List<?>) callbacks.get(app)).size();
        Utils.setContext(app);
        Utils.setContext(new ContextWrapper(app));
        assertEquals(followers, ((List<?>) callbacks.get(app)).size());
    }

    /** HushGram's language lives on the context, so a followed activity doesn't take over its resources. */
    @Test
    public void resourcesAreReadFromTheContextBeforeTheActivity() {
        int[] asked = {0};
        Context languaged = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override
            public Resources getResources() {
                asked[0]++;
                return super.getResources();
            }
        };
        try (ActivityController<Activity> main = Robolectric.buildActivity(Activity.class).setup()) {
            assertSame(main.get(), Utils.getActivity());
            Utils.context = languaged;
            ResourceUtils.getIdentifier(ResourceType.STRING, "hushgram_settings_title");
            assertTrue(asked[0] > 0);
        }
    }
}
