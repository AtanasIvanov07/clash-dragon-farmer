package dev.s25.farmer;

import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeviceSmokeTest {
    @Test public void manifestDeniesUnneededPermissions() {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String p : List.of("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.READ_CONTACTS", "android.permission.READ_SMS", "android.permission.RECORD_AUDIO", "android.permission.CAMERA", "android.permission.SYSTEM_ALERT_WINDOW")) {
            assertEquals(p, PackageManager.PERMISSION_DENIED, c.checkSelfPermission(p));
        }
    }
    @Test public void bundledOcrReadsNumbersWithoutInternet() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap bitmap = Bitmap.createBitmap(1100,220,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.BLACK); p.setTextSize(90); p.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("1 234 567",40,140,p);
        try (Vision v = new Vision(new Store(c))) {
            List<String> lines = v.lines(bitmap);
            assertEquals(Long.valueOf(1234567),Rules.number(String.join(" ",lines)));
        } finally { bitmap.recycle(); }
    }
    @Test public void templateMatcherRejectsDimmedBackground() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext(); Store store = new Store(c);
        store.reset();
        Bitmap b = Bitmap.createBitmap(900,400,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b); canvas.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.rgb(50,130,70)); canvas.drawRect(100,100,400,220,p);
        p.setColor(Color.WHITE); p.setTextSize(60); canvas.drawText("Attack",120,180,p);
        try (Frame frame = new Frame(b,0,0)) {
            store.dimensions(frame);
            store.selection(new Stages.Field("template","smoke_test",""),new int[]{110,110,260,100},List.of(),b);
            try (Vision v = new Vision(store)) {
                assertNotNull(v.match(frame,"smoke_test"));
                Bitmap dim = b.copy(Bitmap.Config.ARGB_8888,true); Canvas d = new Canvas(dim); d.drawColor(0xAA000000);
                try (Frame dark = new Frame(dim,0,0)) { assertNull(v.match(dark,"smoke_test")); }
            }
        } finally { store.reset(); }
    }
    @Test public void rejectsMultilineOcrAsOneAmount() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext(); Store store = new Store(c); store.reset();
        Bitmap b = Bitmap.createBitmap(800,400,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b); canvas.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.BLACK); p.setTextSize(70);
        canvas.drawText("1 234",40,100,p); canvas.drawText("567",40,280,p);
        try (Frame frame = new Frame(b,0,0); Vision v = new Vision(store)) {
            store.dimensions(frame);
            store.selection(new Stages.Field("region","amount",""),new int[]{10,10,700,350},List.of(),b);
            assertNull(Rules.number(v.read(frame,"amount")));
        } finally { store.reset(); }
    }
    @Test public void paymentRegionDetectsChangedDigits() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext(); Store store = new Store(c); store.reset();
        Bitmap b = Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE);
        try (Frame frame = new Frame(b,0,0); Vision v = new Vision(store)) {
            store.dimensions(frame); store.selection(new Stages.Field("region","price",""),new int[]{20,20,100,50},List.of(),b);
            Bitmap second = b.copy(Bitmap.Config.ARGB_8888,true);
            try (Frame next = new Frame(second,0,0)) { assertTrue(v.sameRegion(frame,next,"price")); }
            Bitmap changed = b.copy(Bitmap.Config.ARGB_8888,true); changed.setPixel(50,30,Color.BLACK);
            try (Frame next = new Frame(changed,0,0)) { assertFalse(v.sameRegion(frame,next,"price")); }
        } finally { store.reset(); }
    }
    @Test public void cancelledRecaptureInvalidatesOldFieldsAndAttackOnlySkipsWalls() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext(); Store store = new Store(c); store.reset();
        Bitmap b = Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b); canvas.drawColor(Color.WHITE);
        Paint p = new Paint(); p.setColor(Color.BLACK); canvas.drawRect(50,20,80,100,p);
        try (Frame frame = new Frame(b,0,0)) {
            store.dimensions(frame); store.setWallsEnabled(false);
            Stages.Stage home = Stages.ALL.get(0);
            store.selection(home.fields.get(0),new int[]{20,10,100,100},List.of(),b);
            assertTrue("Wall calibration should not block attacks with walls disabled",store.complete(home));
            store.beginStage(home);
            assertFalse("Cancelled recapture must not reuse old fields",new Store(c).complete(home));
        } finally { store.reset(); store.setWallsEnabled(true); }
    }
}
