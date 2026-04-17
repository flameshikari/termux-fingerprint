package pw.hexed.fingerprint;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.biometric.BiometricPrompt;
import androidx.biometric.BiometricManager;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public class MainActivity extends FragmentActivity {

    private static final int SENSOR_TIMEOUT = 30000;
    private static final int MAX_ATTEMPTS = 5;
    private static final int CONNECT_RETRIES = 30;
    private static final int CONNECT_RETRY_DELAY = 100;

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
    private boolean authStarted = false;
    private int resultPort = -1;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        resultPort = getIntent().getIntExtra("port", -1);
        boolean launcherMode = resultPort <= 0;

        if (launcherMode) {
            setTheme(R.style.Black);
        }
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        resetFingerprintResult();

        FrameLayout layout = new FrameLayout(this);
        if (launcherMode) {
            layout.setBackgroundColor(Color.BLACK);
        }
        setContentView(layout);

        if (launcherMode) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }

        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        );
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            if (canAuthenticate == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
                if (launcherMode) Toast.makeText(this, "No fingerprint scanner found", Toast.LENGTH_SHORT).show();
                appendFingerprintError(ERROR_NO_HARDWARE);
            } else if (canAuthenticate == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                if (launcherMode) Toast.makeText(this, "No fingerprints enrolled", Toast.LENGTH_SHORT).show();
                appendFingerprintError(ERROR_NO_ENROLLED_FINGERPRINTS);
            } else {
                appendFingerprintError("ERROR_UNKNOWN_" + canAuthenticate);
            }
            setAuthResult(AUTH_RESULT_FAILURE);
            postFingerprintResult();
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(this);
        biometricPrompt = new BiometricPrompt(this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
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
                    }
                }
            });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle(" ")
            .setSubtitle(" ")
            .setNegativeButtonText(" ")
            .setConfirmationRequired(false)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || authStarted || biometricPrompt == null || promptInfo == null) return;
        authStarted = true;

        biometricPrompt.authenticate(promptInfo);

        getWindow().getDecorView().postDelayed(() -> {
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
        postedResult = true;

        String payload = buildPayload();
        if (resultPort > 0) {
            sendToServer(payload);
        } else {
            finishAndClearAnimation();
        }
    }

    private void finishAndClearAnimation() {
        finishAffinity();
        overridePendingTransition(0, 0);
    }

    private String buildPayload() {
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
            return json.toString();
        } catch (Exception e) {
            return "{\"auth_result\":\"" + AUTH_RESULT_UNKNOWN + "\"}";
        }
    }

    private void sendToServer(String result) {
        new Thread(() -> {
            for (int i = 0; i < CONNECT_RETRIES; i++) {
                try (Socket socket = new Socket("127.0.0.1", resultPort)) {
                    socket.getOutputStream().write((result + "\n").getBytes());
                    break;
                } catch (Exception e) {
                    try {
                        Thread.sleep(CONNECT_RETRY_DELAY);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            runOnUiThread(this::finishAndClearAnimation);
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
