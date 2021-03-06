package com.onscripter.plus;

import java.io.File;
import java.io.FileFilter;
import java.util.HashMap;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.onscripter.ONScripterView.ONScripterEventListener;
import com.onscripter.ONScripterView.UserMessage;
import com.onscripter.exception.NativeONSException;
import com.onscripter.plus.ONScripterGame.OnGameReadyListener;
import com.onscripter.plus.TwoStateLayout.OnSideMovedListener;
import com.onscripter.plus.VNPreferences.OnLoadVNPrefListener;
import com.onscripter.plus.bugtracking.BugTrackingService;

public class ONScripter extends ActivityPlus implements OnClickListener, OnDismissListener, OnSideMovedListener, OnLoadVNPrefListener, ONScripterEventListener, OnGameReadyListener
{
    public static final String CURRENT_DIRECTORY_EXTRA = "current_directory_extra";
    public static final String SAVE_DIRECTORY_EXTRA = "save_directory_extra";
    public static final String USE_DEFAULT_FONT_EXTRA = "use_default_font_extra";
    public static final String DIALOG_FONT_SCALE_KEY = "dialog_font_scale_key";

    private static final String[] mIgnoreGameExceptions = {
        "getparam: not in a subroutine",
        "return: not in gosub",
        "can't open gloval.sav for writing",
        "Label \"l_000\" is not found.",
        "can't open font file: default.ttf",
        "Label \"l_\" is not found",
        "cannot load corrupt save file",
        "text cannot be displayed in define section",
    };

    private static int HIDE_CONTROLS_TIMEOUT_SECONDS = 0;
    private static int FULLSCREEN_TIMEOUT_SECONDS = App.getContext().getResources()
            .getInteger(R.integer.fullscreen_timeout_seconds) * 1000;

    private static String DISPLAY_CONTROLS_KEY;
    private static String SWIPE_GESTURES_KEY;
    private static String[] SWIPE_GESTURES_VALUES;
    private static String USE_EXTERNAL_VIDEO_KEY;

    private VNSettingsDialog mDialog;
    private TwoStateLayout mLeftLayout;
    private TwoStateLayout mRightLayout;
    private ImageButton2 mBackButton;
    private ImageButton2 mChangeSpeedButton;
    private ImageButton2 mSkipButton;
    private ImageButton2 mAutoButton;
    private ImageButton2 mSettingsButton;
    private ImageButton2 mRightClickButton;
    private ImageButton2 mMouseScrollUpButton;
    private ImageButton2 mMouseScrollDownButton;

    private int mDisplayWidth;
    private int mDisplayHeight;

    private String mCurrentDirectory;
    private String mSaveDirectory;
    private boolean mUseDefaultFont;
    private SharedPreferences mPrefs;
    private VNPreferences mVNPrefs;

    private boolean mAllowLeftBezelSwipe;
    private boolean mAllowRightBezelSwipe;
    private Handler mHideControlsHandler;

    // Kitkat+
    private Handler mImmersiveHandler;

    private ONScripterGame mGame;

    Runnable mHideControlsRunnable = new Runnable() {
        @Override
        public void run() {
            hideControls();
        }
    };

    final private Runnable mInvokeFullscreenRunnable = new Runnable() {
        @Override
        public void run() {
            invokeFullscreen();
        }
    };

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        mCurrentDirectory = getIntent().getStringExtra(CURRENT_DIRECTORY_EXTRA);
        mSaveDirectory = getIntent().getStringExtra(SAVE_DIRECTORY_EXTRA);
        mUseDefaultFont = getIntent().getBooleanExtra(USE_DEFAULT_FONT_EXTRA, false);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        invokeFullscreen();

        // Setup layout
        setContentView(R.layout.onscripter);
        mLeftLayout = (TwoStateLayout)findViewById(R.id.left_menu);
        mRightLayout = (TwoStateLayout)findViewById(R.id.right_menu);
        mBackButton = (ImageButton2)findViewById(R.id.controls_quit_button);
        mChangeSpeedButton = (ImageButton2)findViewById(R.id.controls_change_speed_button);
        mSkipButton = (ImageButton2)findViewById(R.id.controls_skip_button);
        mAutoButton = (ImageButton2)findViewById(R.id.controls_auto_button);
        mSettingsButton = (ImageButton2)findViewById(R.id.controls_settings_button);
        mRightClickButton = (ImageButton2)findViewById(R.id.controls_rclick_button);
        mMouseScrollUpButton = (ImageButton2)findViewById(R.id.controls_scroll_up_button);
        mMouseScrollDownButton = (ImageButton2)findViewById(R.id.controls_scroll_down_button);
        mBackButton.setOnClickListener(this);
        mChangeSpeedButton.setOnClickListener(this);
        mSkipButton.setOnClickListener(this);
        mAutoButton.setOnClickListener(this);
        mSettingsButton.setOnClickListener(this);
        mRightClickButton.setOnClickListener(this);
        mMouseScrollUpButton.setOnClickListener(this);
        mMouseScrollDownButton.setOnClickListener(this);
        mDialog = new VNSettingsDialog(this, mUseDefaultFont ? LauncherActivity.DEFAULT_FONT_PATH
                : mCurrentDirectory + "/" + getString(R.string.default_font_file));
        mDialog.setOnDimissListener(this);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        updateControlPreferences();

        mVNPrefs = ExtSDCardFix.getGameVNPreference(mCurrentDirectory);
        mVNPrefs.setOnLoadVNPrefListener(this);

        mLeftLayout.setOtherLayout(mRightLayout);
        mRightLayout.setOtherLayout(mLeftLayout);

        mLeftLayout.setOnSideMovedListener(this);

        mHideControlsHandler = new Handler();

        // Get the dimensions of the screen
        Point p = new Point();
        getScreenDimensions(p);
        mDisplayWidth = p.x;
        mDisplayHeight = p.y;

        // If Kitkat and higher, we will need to hide the navigation bar immersive mode
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            initImmersiveListeners();
        }

        runSDLApp();
    }

    @Override
    public void autoStateChanged(boolean selected) {
        mAutoButton.setSelected(selected);
    }

    @Override
    public void skipStateChanged(boolean selected) {
        mSkipButton.setSelected(selected);
    }

    @Override
    public void onUserMessage(UserMessage messageId) {
        int stringResId = 0;
        switch (messageId) {
        case CORRUPT_SAVE_FILE:
            stringResId = R.string.message_corrupt_save_file;
            break;
        default:
            return;
        }
        Toast.makeText(this, stringResId, Toast.LENGTH_LONG).show();
    }

    @Override
    public void videoRequested(final String filename, final boolean clickToSkip, final boolean shouldLoop) {
        boolean shouldUseExternalVideo = mPrefs.getBoolean(USE_EXTERNAL_VIDEO_KEY, false);
        mGame.useExternalVideo(shouldUseExternalVideo);
    }

    @Override
    public void onNativeError(NativeONSException e, final String line, final String backtrace) {
        if (e == null) {
            return;
        }
        for (String match: mIgnoreGameExceptions) {
            if (e.getMessage().contains(match)) {
                Log.w("ONScripter", "Ignored = " + e.getMessage());
                return;
            }
        }
        e.printStackTrace();
        final HashMap<String, String> passArgs = new HashMap<String, String>(){
            private static final long serialVersionUID = 1L;
        {
            put("Game Directory", mCurrentDirectory);
            put("Save Directory", mSaveDirectory);
            put("Is Ext SDCard Writable", ExtSDCardFix.isWritable() ? "true" : "false");
            put("Resolution", mGame.getGameWidth() + " x " + mGame.getGameHeight());
            put("Script line", line);
            put("Needs fix", ExtSDCardFix.folderNeedsFix(
                    new File(mCurrentDirectory).getParentFile()) ? "true" : "false");
        }};
        String name = GameUtils.getGameName(mCurrentDirectory);
        if (name == null) {
            name = "*" + new File(mCurrentDirectory).getName();
        }
        BugTrackingService.createCrashReport(ONScripter.this, e.getMessage(), name, backtrace, passArgs);
    }

    @Override
    public void onGameFinished() {
        finish();
    }

    @Override
    public void onReady() {
        fitGameOnScreen();
    }

    private void initImmersiveListeners() {
        mImmersiveHandler = new Handler();
        View v = getWindow().getDecorView();
        if (v != null) {
            v.setOnSystemUiVisibilityChangeListener(new OnSystemUiVisibilityChangeListener() {
                @Override
                public void onSystemUiVisibilityChange(int visibility) {
                    if (View.VISIBLE == visibility) {
                        refreshFullscreenTimer();
                    }
                }
            });
        }
    }

    private void fitGameOnScreen() {
        double screenRatio = mDisplayWidth * 1.0 / mDisplayHeight;
        double gameRatio = mGame.getGameWidth() * 1.0 / mGame.getGameHeight();
        if (screenRatio > gameRatio) {
            // The screen is more widescreen than the game
            mGame.setBoundingHeight(mDisplayHeight);
        } else {
            // The game is more widescreen than the screen
            mGame.setBoundingWidth(mDisplayWidth);

            // Fit the game vertically centered
            View wrapper = findViewById(R.id.game_wrapper);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) wrapper.getLayoutParams();
            params.gravity = Gravity.CENTER;
            wrapper.setLayoutParams(params);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    @SuppressWarnings("deprecation")
    private void getScreenDimensions(Point outDimensions) {
        Display disp = ((WindowManager)this.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();

        int version = android.os.Build.VERSION.SDK_INT;
        if (version < android.os.Build.VERSION_CODES.KITKAT) {
            // Lower than Kitkat, we are not hiding navigation bar so get normal height
            outDimensions.x = disp.getWidth();
            outDimensions.y = disp.getHeight();
        } else {
            // Kitkat, get full height for immersive mode
            disp.getRealSize(outDimensions);
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void invokeFullscreen() {
        int version = android.os.Build.VERSION.SDK_INT;
        if (version >= android.os.Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_IMMERSIVE);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
    }

    private void showVNDialog() {
        // Android 3.2+: a hack to not show the navigation bar when dialogs are shown
        Window dialogWin = mDialog.getWindow();
        dialogWin.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        mDialog.show();
        dialogWin.getDecorView().setSystemUiVisibility(
                getWindow().getDecorView().getSystemUiVisibility());
        dialogWin.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    private void updateControlPreferences() {
        if (DISPLAY_CONTROLS_KEY == null) {
            DISPLAY_CONTROLS_KEY = getString(R.string.settings_controls_display_key);
            SWIPE_GESTURES_KEY = getString(R.string.settings_controls_swipe_key);
            SWIPE_GESTURES_VALUES = getResources().getStringArray(R.array.settings_controls_swipe_values);
            USE_EXTERNAL_VIDEO_KEY = getResources().getString(R.string.settings_external_video_key);
        }
        boolean allowBezelControls = mPrefs.getBoolean(DISPLAY_CONTROLS_KEY, false);
        if (allowBezelControls) {
            hideControls();
        } else {
            showControls();
            mLeftLayout.setEnabled(true);
            mRightLayout.setEnabled(true);
        }

        // Update the bezel swipe conditions
        if (allowBezelControls) {
            int index = -1;
            String gestureValue = mPrefs.getString(SWIPE_GESTURES_KEY,
                    getString(R.string.settings_controls_swipe_default_value));
            for (int i = 0; i < SWIPE_GESTURES_VALUES.length; i++) {
                if (gestureValue.equals(SWIPE_GESTURES_VALUES[i])) {
                    index = i;
                    break;
                }
            }
            // If it did not find the correct value, we will default to 0
            if (index == -1) {
                mPrefs.edit().putString(SWIPE_GESTURES_KEY, SWIPE_GESTURES_VALUES[0]).apply();
                index = 0;
            }
            switch(index) {
            case 0:     // All
                mAllowLeftBezelSwipe = true;
                mAllowRightBezelSwipe = true;
                mLeftLayout.setEnabled(false);
                mRightLayout.setEnabled(false);
                break;
            case 1:     // Left
                mAllowLeftBezelSwipe = true;
                mAllowRightBezelSwipe = false;
                mLeftLayout.setEnabled(false);
                mRightLayout.setEnabled(true);
                break;
            case 2:     // Right
                mAllowLeftBezelSwipe = false;
                mAllowRightBezelSwipe = true;
                mLeftLayout.setEnabled(true);
                mRightLayout.setEnabled(false);
                break;
            }
        } else {
            mAllowLeftBezelSwipe = false;
            mAllowRightBezelSwipe = false;
            mLeftLayout.setEnabled(true);
            mRightLayout.setEnabled(true);
        }
    }

    private void runSDLApp() {
        boolean shouldRenderOutline = mPrefs.getBoolean(getString(R.string.settings_render_font_outline_key),
                getResources().getBoolean(R.bool.render_font_outline));
        boolean useHQAudio = mPrefs.getBoolean(getString(R.string.settings_use_hq_audio_key), false);

        if (mUseDefaultFont) {
            mGame = ONScripterGame.newInstance(mCurrentDirectory, LauncherActivity.DEFAULT_FONT_PATH, mSaveDirectory, useHQAudio, shouldRenderOutline);
        } else {
            mGame = ONScripterGame.newInstance(mCurrentDirectory, null, mSaveDirectory, useHQAudio, shouldRenderOutline);
        }

        // Attach the game fragment
        FragmentManager fm = getFragmentManager();
        FragmentTransaction transaction = fm.beginTransaction();
        transaction.add(R.id.game_wrapper, mGame);
        transaction.commitAllowingStateLoss();

        mGame.setONScripterEventListener(this);
        mGame.setOnGameReadyListener(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scanAndPromptForMultipleScripts();
    }

    public int getGameHeight() {
        return mGame != null ? mGame.getGameHeight() : 0;
    }

    public int getGameFontSize() {
        return mGame.getGameFontSize();
    }

    @Override
    public void onClick(View v) {
        boolean refreshTimer = true;
        switch(v.getId()) {
        case R.id.controls_quit_button:
            removeHideControlsTimer();
            mGame.exitApp();
            return;
        case R.id.controls_change_speed_button:
            mGame.sendNativeKeyPress(KeyEvent.KEYCODE_O);
            break;
        case R.id.controls_skip_button:
            mGame.sendNativeKeyPress(KeyEvent.KEYCODE_S);
            break;
        case R.id.controls_auto_button:
            mGame.sendNativeKeyPress(KeyEvent.KEYCODE_A);
            break;
        case R.id.controls_settings_button:
            removeHideControlsTimer();
            showVNDialog();
            refreshTimer = false;
            break;
        case R.id.controls_rclick_button:
            mGame.sendNativeKeyPress(KeyEvent.KEYCODE_BACK);
            break;
        case R.id.controls_scroll_up_button:
            mGame.sendNativeKeyPress(KeyEvent.KEYCODE_DPAD_LEFT);
            break;
        case R.id.controls_scroll_down_button:
            mGame.sendNativeKeyPress(KeyEvent.KEYCODE_DPAD_RIGHT);
            break;
        default:
            return;
        }
        if (refreshTimer) {
            refreshHideControlsTimer();
        }
    }

    @Override
    public void onLoadVNPref(Result result) {
        if (result == Result.NO_ISSUES) {
            // Load scale factor
            double scaleFactor = mVNPrefs.getFloat(DIALOG_FONT_SCALE_KEY, 1);
            mDialog.setFontScalingFactor(scaleFactor);
            mGame.setFontScaling(scaleFactor);
        }

        if (result == Result.NO_MEMORY) {
            AlertDialog.Builder dialog = new Builder(this);
            dialog.setTitle(getString(R.string.app_name));
            dialog.setMessage(R.string.message_cannot_write_pref);
            dialog.setPositiveButton(android.R.string.ok, null);
            dialog.show();
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();

        // For Android 4-4.3, it will show low profile
        int version = android.os.Build.VERSION.SDK_INT;
        if (version < android.os.Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        } else if (version >= android.os.Build.VERSION_CODES.KITKAT) {
            invokeFullscreen();
        }
    }

    @Override
    protected void onUserLeaveHint()
    {
        super.onUserLeaveHint();
        if( mGame != null ) {
            mGame.onUserLeaveHint();
        }
    }

    @Override
    protected void onDestroy()
    {
        if( mGame != null ) {
            mGame.exitApp();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Do not allow back button when playing video, skip it otherwise
        if (mGame.isVideoShown()) {
            mGame.finishVideo();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        updateControlPreferences();
        double scaleFactor = mDialog.getFontScalingFactor();
        mGame.setFontScaling(scaleFactor);
        mVNPrefs.putFloat(DIALOG_FONT_SCALE_KEY, (float) scaleFactor);
        mVNPrefs.commit();
    }

    @Override
    public void onLeftSide(TwoStateLayout v) {
    }

    @Override
    public void onRightSide(TwoStateLayout v) {
        if (v == mLeftLayout) {
            refreshHideControlsTimer();
        }
    }

    private void hideControls() {
        hideControls(true);
    }

    private void showControls() {
        showControls(true);
    }

    private void hideControls(boolean animate) {
        mLeftLayout.moveLeft(animate);
        mRightLayout.moveRight(animate);
    }

    private void showControls(boolean animate) {
        mLeftLayout.moveRight(animate);
        mRightLayout.moveLeft(animate);
    }

    private void removeHideControlsTimer() {
        if (mAllowRightBezelSwipe || mAllowLeftBezelSwipe) {
            mHideControlsHandler.removeCallbacks(mHideControlsRunnable);
        }
    }

    private void refreshHideControlsTimer() {
        if (mAllowRightBezelSwipe || mAllowLeftBezelSwipe) {
            removeHideControlsTimer();
            if (HIDE_CONTROLS_TIMEOUT_SECONDS == 0) {
                HIDE_CONTROLS_TIMEOUT_SECONDS = getResources().getInteger(R.integer.hide_controls_timeout_seconds);
            }
            mHideControlsHandler.postDelayed(mHideControlsRunnable, 1000 * HIDE_CONTROLS_TIMEOUT_SECONDS);
        }
    }

    private void removeFullscreenTimer() {
        mImmersiveHandler.removeCallbacks(mInvokeFullscreenRunnable);
    }

    private void refreshFullscreenTimer() {
        removeFullscreenTimer();
        mImmersiveHandler.postDelayed(mInvokeFullscreenRunnable, FULLSCREEN_TIMEOUT_SECONDS);
    }

    private void scanAndPromptForMultipleScripts() {
        final String dontShowAgainKey = getString(R.string.settings_multiple_scripts_dont_show_again_key);
        if (!mPrefs.getBoolean(dontShowAgainKey, false)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    // Scan the sub-directories one level for a script file name and
                    // add to list when found, if found it skips to next sub-directory
                    File[] files = new File(mCurrentDirectory).listFiles(new FileFilter() {
                        @Override
                        public boolean accept(File pathname) {
                            return pathname.isDirectory() && pathname.canRead();
                        }
                    });
                    if (files != null) {
                        final StringBuilder sb = new StringBuilder();
                        final String[] scriptNames = {"nscript.dat", "0.txt", "00.txt"};
                        for (File subdir: files) {
                            String subdirName = subdir.getName();
                            for (String scriptName: scriptNames) {
                                File scriptFile = new File(subdir + "/" + scriptName);
                                if (scriptFile.exists() && scriptFile.canRead()) {
                                    sb.append("\n\t- " + subdirName + "/" + scriptName);
                                    break;
                                }
                            }
                        }
                        if (sb.length() > 0) {
                            // Show dialog informing them that there are multiple script files
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    new Builder(ONScripter.this)
                                    .setTitle(getString(R.string.app_name))
                                    .setMessage(getString(R.string.message_multiple_script_files) + sb.toString())
                                    .setNegativeButton(R.string.dialog_multiple_scripts_dont_show_again,
                                            new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            mPrefs.edit().putBoolean(dontShowAgainKey, true).apply();
                                        }
                                    })
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                                }
                            });
                        }
                    }
                }
            }).start();
        }
    }
}
