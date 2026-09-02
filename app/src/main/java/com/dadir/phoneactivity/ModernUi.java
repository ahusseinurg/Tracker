package com.dadir.phoneactivity;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

final class ModernUi {
    static final int NAVY=0xff102a43, BLUE=0xff2563eb, GREEN=0xff16835f, SLATE=0xff64748b;
    static final int BG=0xfff3f6fa, CARD=0xffffffff, BORDER=0xffdbe3ec, TEXT=0xff172033, MUTED=0xff64748b;
    private ModernUi() {}
    static void fill(View view,int color,float radiusDp){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(view.getContext(),radiusDp));view.setBackground(d);}
    static void outlined(View view,int color,int stroke,float radiusDp){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(view.getContext(),radiusDp));d.setStroke((int)dp(view.getContext(),1),stroke);view.setBackground(d);}
    static void elevate(View view,float amount){view.setElevation(dp(view.getContext(),amount));}
    static float dp(Context c,float value){return value*c.getResources().getDisplayMetrics().density;}
}
