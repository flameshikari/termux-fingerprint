package pw.hexed.fingerprint;

import java.net.Socket;
import java.util.concurrent.Executor;

import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private void sendToServer(String result) {
        new Thread(() -> {
            try {
                Socket socket = new Socket("127.0.0.1", 10451);
                socket.getOutputStream().write((result + "\n").getBytes());
                socket.close();
            } catch (Exception e) {
                // to do
            }
        }).start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.BLACK);
        setContentView(layout);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.BLACK);
        }

        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
        );

        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(
                        BiometricPrompt.AuthenticationResult result) {
                    sendToServer("0");
                    finishAffinity();
                }

                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    sendToServer(Integer.toString(errorCode));
                    finishAffinity();
                }

                @Override
                public void onAuthenticationFailed() {
                    // to do
                }
            });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle(" ")
            .setSubtitle(" ")
            .setNegativeButtonText(" ")
            .setConfirmationRequired(false)
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build();

        biometricPrompt.authenticate(promptInfo);
    }
}
