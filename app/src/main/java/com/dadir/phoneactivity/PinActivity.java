package com.dadir.phoneactivity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class PinActivity extends Activity {
    private EditText pin;
    private TextView message;
    private String firstPin;
    private boolean setup;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setup = !LockStore.hasPin(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28),dp(28),dp(28),dp(28)); root.setBackgroundColor(Color.rgb(16,42,67));
        TextView title = text(setup ? "Create your PIN" : "Phone Archive locked", 26, true);
        root.addView(title);
        message = text(setup ? "Choose a 4–8 digit PIN." : "Enter your PIN to continue.", 15, false);
        message.setPadding(0,dp(8),0,dp(20)); root.addView(message);
        pin = new EditText(this); pin.setTextColor(Color.WHITE); pin.setHintTextColor(Color.LTGRAY); pin.setHint("PIN");
        pin.setTextSize(24); pin.setGravity(Gravity.CENTER); pin.setSingleLine(true);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        root.addView(pin,new LinearLayout.LayoutParams(-1,dp(62)));
        Button unlock = new Button(this); unlock.setText(setup ? "Continue" : "Unlock"); unlock.setTextSize(17); unlock.setAllCaps(false);
        unlock.setOnClickListener(v -> submit(unlock));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1,dp(58)); bp.setMargins(0,dp(16),0,0); root.addView(unlock,bp);
        setContentView(root);
    }

    private void submit(Button button) {
        String value = pin.getText().toString();
        if (value.length() < 4 || value.length() > 8) { message.setText("PIN must contain 4–8 digits."); pin.setText(""); return; }
        if (setup) {
            if (firstPin == null) { firstPin=value; pin.setText(""); message.setText("Enter the same PIN again."); button.setText("Save PIN"); return; }
            if (!firstPin.equals(value)) { firstPin=null; pin.setText(""); message.setText("PINs did not match. Start again."); button.setText("Continue"); return; }
            if (!LockStore.setPin(this,value)) { message.setText("PIN could not be saved."); return; }
        } else if (!LockStore.verify(this,value)) { pin.setText(""); message.setText("Incorrect PIN. Try again."); return; }
        startActivity(new Intent(this,MainActivity.class)); finish();
    }

    private TextView text(String value,int size,boolean bold) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.WHITE); t.setGravity(Gravity.CENTER); if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t; }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
