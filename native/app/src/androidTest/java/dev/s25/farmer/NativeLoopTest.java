package dev.s25.farmer;

import android.app.UiAutomation;
import android.content.*;
import android.os.SystemClock;
import android.view.accessibility.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.Assert.*;

/** Runs real Android capture/OCR/gestures against a synthetic companion scene. */
@RunWith(AndroidJUnit4.class)
public class NativeLoopTest {
    private final Map<String,int[]> regions = Map.ofEntries(
        Map.entry("home",new int[]{30,415,150,43}), Map.entry("home_gold",new int[]{715,20,250,38}),
        Map.entry("home_anchor",new int[]{270,190,65,65}),
        Map.entry("home_elixir",new int[]{715,70,250,38}),Map.entry("find_match",new int[]{690,345,240,50}),
        Map.entry("scout",new int[]{810,335,140,45}),Map.entry("dragon",new int[]{140,415,165,44}),
        Map.entry("dragon_count",new int[]{330,415,80,45}),
        Map.entry("loot_gold",new int[]{25,75,175,38}),Map.entry("loot_elixir",new int[]{25,125,175,38}),
        Map.entry("battle",new int[]{30,335,180,44}),Map.entry("result",new int[]{660,365,270,45}),
        Map.entry("wall_gold",new int[]{410,405,145,38}),Map.entry("wall_gold_cost",new int[]{408,444,160,38}),
        Map.entry("wall_elixir",new int[]{630,405,150,38}),Map.entry("wall_elixir_cost",new int[]{628,444,160,38}),
        Map.entry("wall_confirm",new int[]{340,133,240,48}),Map.entry("confirm_gold",new int[]{440,298,140,40}),
        Map.entry("confirm_elixir",new int[]{440,298,140,40}),Map.entry("confirm_gold_cost",new int[]{445,346,200,40}),
        Map.entry("confirm_elixir_cost",new int[]{445,346,200,40})
    );
    private int[] scale(int[] p,Frame f) {
        int[] out=p.clone(); for(int i=0;i<p.length;i++)out[i]=Math.round(p[i]*(i%2==0?f.width()/1000f:f.height()/500f)); return out;
    }
    private void scene(Context context,String scene,String resource,boolean reset) throws Exception {
        Intent i=new Intent().setClassName("dev.s25.testscene","dev.s25.testscene.SceneActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("scene",scene).putExtra("resource",resource).putExtra("reset",reset);
        context.startActivity(i); Thread.sleep(1300);
    }
    @Test public void realAndroidLoopOnSyntheticScene() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        UiAutomation automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var info=automation.getServiceInfo(); info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS; automation.setServiceInfo(info);
        // Instrumentation restarts this process, which marks its old service as crashed.
        // Rebind only on the dedicated emulator used by this test.
        assertTrue("Synthetic integration test requires an emulator", android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone"));
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try {
            android.provider.Settings.Secure.putString(context.getContentResolver(), "enabled_accessibility_services", "");
            Thread.sleep(300);
            android.provider.Settings.Secure.putString(context.getContentResolver(), "enabled_accessibility_services", "dev.s25.farmer/dev.s25.farmer.MacroService");
            android.provider.Settings.Secure.putInt(context.getContentResolver(), "accessibility_enabled", 1);
        } finally { automation.dropShellPermissionIdentity(); }
        long until=SystemClock.elapsedRealtime()+10000;
        while(MacroService.getInstance()==null&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        MacroService service=MacroService.getInstance(); assertNotNull("Enable Dragon Farmer Accessibility on the emulator first",service);
        Store store=new Store(context);store.reset();
        Method capture=MacroService.class.getDeclaredMethod("capture",boolean.class);capture.setAccessible(true);
        String[] scenes={"home","menu","scout","battle","result","selected","confirm","confirm"};
        for(int i=0;i<Stages.ALL.size();i++) {
            scene(context,scenes[i],i==7?"elixir":"gold",i==0);
            try(Frame frame=(Frame)capture.invoke(service,false)) {
                store.dimensions(frame);
                for(Stages.Field field:Stages.ALL.get(i).fields) {
                    List<int[]> points=new ArrayList<>(); int[] r=null;
                    if(field.key.equals("home_blank"))points.add(scale(new int[]{650,350},frame));
                    else if(field.key.equals("walls")){points.add(scale(new int[]{300,220},frame));points.add(scale(new int[]{400,220},frame));}
                    else if(field.key.equals("deploy")){points.add(scale(new int[]{50,220},frame));points.add(scale(new int[]{930,220},frame));}
                    else r=scale(regions.get(field.key),frame);
                    store.selection(field,r,points,frame.bitmap);
                }
            }
        }
        assertTrue(store.missing().toString(),store.missing().isEmpty());
        store.set("dragonCount",2);store.set("maxAttacks",2);store.set("maxMinutes",5);
        scene(context,"home","gold",true);
        instrumentation.runOnMainSync(()->service.prepare(MacroService.FARM));
        Thread.sleep(1500);
        boolean clicked=false;
        for(AccessibilityWindowInfo window:automation.getWindows()) {
            AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
            for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText("START")) {
                if(n.isClickable()){clicked=n.performAction(AccessibilityNodeInfo.ACTION_CLICK);if(clicked)break;}
            }
            if(clicked)break;
        }
        assertTrue("Floating START control was not clickable",clicked);
        until=SystemClock.elapsedRealtime()+180000;
        while(service.busy()&&SystemClock.elapsedRealtime()<until)Thread.sleep(500);
        assertFalse("Session timed out: "+service.status(),service.busy());
        assertEquals("2 attacks and 4 verified wall upgrades.",service.status());
        // Exercise the production payment adapter directly against a real Android
        // confirmation. A changed price and insufficient reserve must send no tap.
        scene(context,"confirm","gold",true);
        Method adapter=MacroService.class.getDeclaredMethod("port",Vision.class);adapter.setAccessible(true);
        try(Vision vision=new Vision(new Store(context))) {
            Farmer.Port port=(Farmer.Port)adapter.invoke(service,vision);
            assertThrows(Exception.class,()->port.pay("gold",999999,1000000,10000000));
            assertThrows(Exception.class,()->port.pay("gold",2000000,9500000,10000000));
            try(Frame frame=(Frame)capture.invoke(service,false)) {
                assertNotNull(vision.match(frame,"wall_confirm"));
                assertEquals(Long.valueOf(10000000),Rules.number(vision.read(frame,"home_gold")));
            }
            scene(context,"home","gold",true);
            instrumentation.runOnMainSync(()->service.prepare(MacroService.DIAGNOSE));
            Thread.sleep(1000);
            int[] covered={40,40};
            Exception blocked=assertThrows(Exception.class,()->port.point(covered,"home"));
            assertTrue(blocked.toString(),blocked.getMessage().contains("covers") || (blocked.getCause()!=null && blocked.getCause().getMessage().contains("covers")));
            // InputManager-injected keys can bypass Accessibility's hardware-key
            // filter. Verify the handler here; virtual hardware is checked separately.
            instrumentation.runOnMainSync(()-> {
                assertTrue(service.onKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_VOLUME_DOWN)));
                assertTrue(service.onKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_VOLUME_DOWN)));
            });
            Thread.sleep(300);
            assertFalse(service.busy()); assertEquals("Stopped with Volume Down",service.status());
            assertThrows(Exception.class,()->port.point(new int[]{150,920},"home"));
        }
    }
}
