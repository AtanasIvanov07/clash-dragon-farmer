package dev.s25.farmer;

import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CountOcrTest {
    @Test public void readsSmallCounterAtLandscapeScaling() throws Exception {
        Store store=new Store(InstrumentationRegistry.getInstrumentation().getTargetContext());
        try(Vision vision=new Vision(store)) {
            for(int n: new int[]{18,2,1,0}) {
                Bitmap bitmap=Bitmap.createBitmap(192,97,Bitmap.Config.ARGB_8888);
                Canvas c=new Canvas(bitmap);c.drawColor(Color.rgb(226,231,219));c.scale(2.4f,2.16f);
                Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(27);
                c.drawText("x"+n,5,30,p);
                Bitmap scaled=Bitmap.createScaledBitmap(bitmap,384,194,true);
                try { String raw=vision.counterText(scaled);assertEquals("Small counter OCR: "+raw,Integer.valueOf(n),Rules.troopCount(raw)); }
                finally { scaled.recycle(); bitmap.recycle(); }
            }
        }
    }
    @Test public void readsTwoDigitAndDepletedCounters() throws Exception {
        Store store=new Store(InstrumentationRegistry.getInstrumentation().getTargetContext());
        try(Vision vision=new Vision(store)) {
            for(int n: new int[]{18,2,1,0}) {
                Bitmap bitmap=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888);
                Canvas c=new Canvas(bitmap);c.drawColor(Color.WHITE);
                Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(100);
                c.drawText("x"+n,30,130,p);
                try { String raw=vision.counterText(bitmap);assertEquals("OCR: "+raw,Integer.valueOf(n),Rules.troopCount(raw)); }
                finally { bitmap.recycle(); }
            }
        }
    }
}
