package dev.s25.testscene;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.view.*;

/** Synthetic local test scene. This fixture APK is never distributed to the user. */
public final class SceneActivity extends Activity {
    String scene = "home", resource = "gold";
    int gold=10000000, elixir=10000000, wall=0, dragons=0;
    int[] levels={17,17};
    final Handler handler = new Handler(Looper.getMainLooper());
    Board board;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        board=new Board(); setContentView(board); apply(getIntent());
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); apply(i); }
    void apply(Intent intent) {
        handler.removeCallbacksAndMessages(null);
        if (intent.getBooleanExtra("reset",false)) {gold=10000000;elixir=10000000;levels=new int[]{17,17};dragons=0;wall=0;}
        if(intent.hasExtra("scene")) scene=intent.getStringExtra("scene");
        if(intent.hasExtra("resource"))resource=intent.getStringExtra("resource");
        board.invalidate();
    }
    void next(String s) { scene=s;board.invalidate(); }
    final class Board extends View {
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Board(){super(SceneActivity.this);setContentDescription("Synthetic farming test scene");}
        void text(Canvas c,String s,float x,float y,int color){p.setColor(color);p.setTextSize(27);p.setTypeface(Typeface.DEFAULT_BOLD);c.drawText(s,x,y,p);}
        void button(Canvas c,String s,float x,float y,float w,float h,int color){p.setColor(color);c.drawRect(x,y,x+w,y+h,p);text(c,s,x+12,y+34,Color.WHITE);}
        @Override protected void onDraw(Canvas c){
            c.drawColor(Color.rgb(226,231,219));c.save();c.scale(getWidth()/1000f,getHeight()/500f);
            text(c,"SYNTHETIC TEST SCENE",20,40,Color.DKGRAY);
            text(c,Integer.toString(gold),720,50,Color.BLACK);text(c,Integer.toString(elixir),720,100,Color.BLACK);
            if(scene.equals("home")||scene.equals("selected")){
                button(c,"Attack",20,410,180,65,Color.rgb(40,95,170));
                button(c,"W1",270,190,65,65,Color.DKGRAY);button(c,"W2",370,190,65,65,Color.DKGRAY);
                if(scene.equals("selected")){
                    text(c,"Wall (Level "+levels[wall]+")",370,310,Color.BLACK);
                    button(c,"Gold",400,400,180,90,Color.rgb(140,90,20));text(c,"2000000",412,472,Color.WHITE);
                    button(c,"Elixir",620,400,180,90,Color.rgb(130,45,140));text(c,"3000000",632,472,Color.WHITE);
                }
            }else if(scene.equals("menu"))button(c,"Find a Match",680,340,280,80,Color.rgb(35,120,60));
            else if(scene.equals("scout")||scene.equals("battle")){
                text(c,"1000000",30,105,Color.BLACK);text(c,"1000000",30,155,Color.BLACK);
                button(c,"Dragons",130,410,190,65,Color.rgb(120,35,140));
                text(c,"x"+Math.max(0,2-dragons),335,445,Color.BLACK);
                if(scene.equals("scout"))button(c,"Next",800,330,170,70,Color.rgb(180,100,25));
                else button(c,"Surrender",20,330,210,65,Color.rgb(170,35,30));
            }else if(scene.equals("result"))button(c,"Return Home",650,360,320,80,Color.rgb(25,110,80));
            else if(scene.equals("confirm")){
                p.setColor(Color.rgb(246,245,235));c.drawRect(260,110,810,430,p);
                text(c,"Wall upgrade",350,170,Color.BLACK);
                button(c,resource.equals("gold")?"Gold":"Elixir",430,290,270,110,resource.equals("gold")?Color.rgb(140,90,20):Color.rgb(130,45,140));
                text(c,resource.equals("gold")?"2000000":"3000000",450,375,Color.WHITE);
            }
            c.restore();
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(e.getAction()!=MotionEvent.ACTION_UP)return true;
            float x=e.getX()*1000/getWidth(),y=e.getY()*500/getHeight();
            if(scene.equals("home")||scene.equals("selected")){
                if(x<210&&y>400)next("menu");
                else if(x>=270&&x<335&&y>=190&&y<255){wall=0;next("selected");}
                else if(x>=370&&x<435&&y>=190&&y<255){wall=1;next("selected");}
                else if(scene.equals("selected")&&y>=400&&x>=400&&x<580){resource="gold";next("confirm");}
                else if(scene.equals("selected")&&y>=400&&x>=620&&x<800){resource="elixir";next("confirm");}
                else if(x>600&&y>300&&y<390)next("home");
            }else if(scene.equals("menu")&&x>680&&y>340){next("loading");handler.postDelayed(()->next("scout"),1000);}
            else if(scene.equals("scout")&&x>800&&y>330&&y<405){next("loading");handler.postDelayed(()->next("scout"),1000);}
            else if((scene.equals("scout")||scene.equals("battle"))&&y>175&&y<250&&(x<100||x>880)){
                dragons++;next("battle");if(dragons>=2)handler.postDelayed(()->next("result"),2500);
            }else if(scene.equals("result")&&x>650&&y>360){dragons=0;next("home");}
            else if(scene.equals("confirm")&&x>430&&x<700&&y>290&&y<400){if(resource.equals("gold"))gold-=2000000;else elixir-=3000000;levels[wall]++;next("selected");}
            performClick();return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
