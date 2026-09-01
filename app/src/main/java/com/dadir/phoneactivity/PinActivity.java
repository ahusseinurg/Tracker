package com.dadir.phoneactivity;

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
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

public class PinActivity extends FragmentActivity {
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
        TextView title = text(setup ? "Create your Maping PIN" : "Maping locked", 26, true);
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
        if(!setup&&getSharedPreferences("media_library",MODE_PRIVATE).getBoolean("biometric_unlock",true)){
            int authenticators=BiometricManager.Authenticators.BIOMETRIC_STRONG|BiometricManager.Authenticators.BIOMETRIC_WEAK;
            if(BiometricManager.from(this).canAuthenticate(authenticators)==BiometricManager.BIOMETRIC_SUCCESS){
                Button biometric=new Button(this);biometric.setText("Use fingerprint or face");biometric.setTextSize(17);biometric.setAllCaps(false);biometric.setOnClickListener(v->showBiometric(authenticators));
                LinearLayout.LayoutParams bioParams=new LinearLayout.LayoutParams(-1,dp(58));bioParams.setMargins(0,dp(10),0,0);root.addView(biometric,bioParams);
            }
        }
        setContentView(root);
        if(!setup&&getSharedPreferences("media_library",MODE_PRIVATE).getBoolean("biometric_unlock",true))showBiometricIfAvailable();
    }

    private void showBiometricIfAvailable(){int a=BiometricManager.Authenticators.BIOMETRIC_STRONG|BiometricManager.Authenticators.BIOMETRIC_WEAK;if(BiometricManager.from(this).canAuthenticate(a)==BiometricManager.BIOMETRIC_SUCCESS)showBiometric(a);}
    private void showBiometric(int authenticators){
        BiometricPrompt prompt=new BiometricPrompt(this,ContextCompat.getMainExecutor(this),new BiometricPrompt.AuthenticationCallback(){
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){super.onAuthenticationSucceeded(result);LockStore.unlock();startActivity(new Intent(PinActivity.this,MainActivity.class));finish();}
            @Override public void onAuthenticationError(int code,CharSequence error){super.onAuthenticationError(code,error);if(code!=BiometricPrompt.ERROR_USER_CANCELED&&code!=BiometricPrompt.ERROR_NEGATIVE_BUTTON)message.setText(error);}
        });
        prompt.authenticate(new BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Maping").setSubtitle("Use your fingerprint or face").setNegativeButtonText("Use PIN").setAllowedAuthenticators(authenticators).build());
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
