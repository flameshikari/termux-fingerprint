package pw.hexed.fingerprint;

import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.biometric.BiometricManager;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {

    private static final int SENSOR_TIMEOUT = 30000;
    private static final int MAX_ATTEMPTS = 5;

    private static final String ERROR_NO_HARDWARE = "ERROR_NO_HARDWARE";
    private static final String ERROR_NO_ENROLLED_FINGERPRINTS = "ERROR_NO_ENROLLED_FINGERPRINTS";
    private static final String ERROR_TIMEOUT = "ERROR_TIMEOUT";
    private static final String ERROR_LOCKOUT = "ERROR_LOCKOUT";
    private static final String ERROR_TOO_MANY_FAILED_ATTEMPTS = "ERROR_TOO_MANY_FAILED_ATTEMPTS";
    private static final String ERROR_CANCEL = "ERROR_CANCEL";
    private static final String ERROR_USER_CANCELED = "ERROR_USER_CANCELED";
    private static final String ERROR_CANCELED = "ERROR_CANCELED";

    private static final String AUTH_RESULT_SUCCESS = "AUTH_RESULT_SUCCESS";
    private static final String AUTH_RESULT_FAILURE = "AUTH_RESULT_FAILURE";
    private static final String AUTH_RESULT_UNKNOWN = "AUTH_RESULT_UNKNOWN";

    private FingerprintResult fingerprintResult = new FingerprintResult();
    private boolean postedResult = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        resetFingerprintResult();

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

        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        );
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            if (canAuthenticate == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
                Toast.makeText(this, "No fingerprint scanner found!", Toast.LENGTH_SHORT).show();
                appendFingerprintError(ERROR_NO_HARDWARE);
            } else if (canAuthenticate == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                Toast.makeText(this, "No fingerprints enrolled", Toast.LENGTH_SHORT).show();
                appendFingerprintError(ERROR_NO_ENROLLED_FINGERPRINTS);
            } else {
                appendFingerprintError("ERROR_UNKNOWN_" + canAuthenticate);
            }
            setAuthResult(AUTH_RESULT_FAILURE);
            postFingerprintResult();
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    // Handle user cancellation, programmatic/system cancellation, and negative button
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                        appendFingerprintError(ERROR_USER_CANCELED);
                    }
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        appendFingerprintError(ERROR_CANCEL);
                    }
                    if (errorCode == BiometricPrompt.ERROR_CANCELED) {
                        appendFingerprintError(ERROR_CANCELED);
                    }
                    if (errorCode == BiometricPrompt.ERROR_LOCKOUT) {
                        appendFingerprintError(ERROR_LOCKOUT);
                    }
                    if (fingerprintResult.failedAttempts >= MAX_ATTEMPTS) {
                        appendFingerprintError(ERROR_TOO_MANY_FAILED_ATTEMPTS);
                        appendFingerprintError(ERROR_LOCKOUT);
                    }
                    setAuthResult(AUTH_RESULT_FAILURE);
                    if (errString != null && errString.toString().matches("^ERROR_[A-Z_]+$")) {
                        appendFingerprintError(errString.toString());
                    }
                    postFingerprintResult();
                }

                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    setAuthResult(AUTH_RESULT_SUCCESS);
                    postFingerprintResult();
                }

                @Override
                public void onAuthenticationFailed() {
                    addFailedAttempt();
                    if (fingerprintResult.failedAttempts >= MAX_ATTEMPTS) {
                        appendFingerprintError(ERROR_TOO_MANY_FAILED_ATTEMPTS);
                        appendFingerprintError(ERROR_LOCKOUT);
                        setAuthResult(AUTH_RESULT_FAILURE);
                        postFingerprintResult();
                        finishAffinity();
                        System.exit(0);
                    }
                }
            });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle(" ")
            .setSubtitle(" ")
            .setNegativeButtonText(" ")
            .setConfirmationRequired(false)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build();

        biometricPrompt.authenticate(promptInfo);

        decorView.postDelayed(() -> {
            if (!postedResult) {
                appendFingerprintError(ERROR_TIMEOUT);
                biometricPrompt.cancelAuthentication();
                setAuthResult(AUTH_RESULT_FAILURE);
                postFingerprintResult();
            }
        }, SENSOR_TIMEOUT);
    }

    private void postFingerprintResult() {
        if (postedResult) return;
        try {
            JSONObject json = new JSONObject();
            List<String> filteredErrors = new ArrayList<>();
            for (String error : fingerprintResult.errors) {
                if (error.matches("^ERROR_[A-Z_]+$") || error.startsWith("ERROR_UNKNOWN_")) {
                    filteredErrors.add(error);
                }
            }
            Collections.sort(filteredErrors);
            JSONArray errorsArr = new JSONArray();
            for (String error : filteredErrors) {
                errorsArr.put(error);
            }
            json.put("errors", errorsArr);
            json.put("failed_attempts", fingerprintResult.failedAttempts);
            json.put("auth_result", fingerprintResult.authResult);
            sendToServer(json.toString());
        } catch (Exception e) {
            // ignore errors
        }
        postedResult = true;
        finishAffinity();
    }

    private void sendToServer(String result) {
        new Thread(() -> {
            try {
                Socket socket = new Socket("127.0.0.1", 10451);
                socket.getOutputStream().write((result + "\n").getBytes());
                socket.close();
            } catch (Exception e) {
                // ignore errors
            }
        }).start();
    }

    private void resetFingerprintResult() {
        fingerprintResult = new FingerprintResult();
        postedResult = false;
    }

    private void addFailedAttempt() {
        fingerprintResult.failedAttempts++;
    }

    private void appendFingerprintError(String error) {
        fingerprintResult.errors.add(error);
    }

    private void setAuthResult(String authResult) {
        fingerprintResult.authResult = authResult;
    }

    static class FingerprintResult {
        public String authResult = AUTH_RESULT_UNKNOWN;
        public int failedAttempts = 0;
        public List<String> errors = new ArrayList<>();
    }
}
