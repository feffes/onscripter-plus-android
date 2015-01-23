package com.onscripter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;

import android.app.Activity;
import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

public class ONScripterTracer {
    public static String TRACE_FILE_NAME = "trace.log";

    private static File sTraceFile;
    private static boolean sIsOpened = false;
    private static StringBuilder sBuffer;
    private static long sStartTime = 0;
    private static long sSkipTime = 0;
    private static boolean sAllowPlayback = false;

    private final static char KEY_EVENT = 'k';
    private final static char MOUSE_EVENT = 'm';
    private final static char CRASH_EVENT = 'c';
    private final static char LOAD_EVENT = 'l';

    private ONScripterTracer(){}

    public static void init(Context ctx) {
        sTraceFile = new File(ctx.getApplicationContext().getFilesDir() + "/" + TRACE_FILE_NAME);
    }

    public static void traceKeyEvent(int keyCode, int down) {
        traceText(KEY_EVENT + "," + keyCode + "," + down);
    }

    public static void traceMouseEvent(int x, int y, int action) {
        traceText(MOUSE_EVENT + "," + x + "," + y + "," + action);
    }

    public static void traceVideoStartEvent() {
        sSkipTime -= System.currentTimeMillis();
    }

    public static void traceVideoEndEvent() {
        sSkipTime += System.currentTimeMillis();
    }

    public static void traceLoadEvent(String filename, String savePath) {
        reset();
        traceText(LOAD_EVENT + "," + filename + "," + (savePath != null ? savePath : ""));
    }

    public static void traceCrash() {
        traceText(CRASH_EVENT + "");
    }

    public static boolean open() {
        return open(true);
    }

    public static void allowPlayback(boolean flag) {
        sAllowPlayback = flag;
    }

    public static boolean playbackEnabled() {
        return sAllowPlayback;
    }

    public static synchronized boolean open(boolean append) {
        if (!sIsOpened) {
            sIsOpened = true;
            if (!append) {
                sTraceFile.delete();
                reset();
            } else if (sStartTime > 0) {
                // Reopened so we need to skip the time that was closed
                sSkipTime += System.currentTimeMillis();
            }
            if (sStartTime == 0) {
                sStartTime = System.currentTimeMillis();
            }
            if (sBuffer == null) {
                sBuffer = new StringBuilder();
            }
            return true;
        }
        return false;
    }

    public static synchronized void close() {
        if (sIsOpened && sBuffer != null) {
            sSkipTime -= System.currentTimeMillis();
            sIsOpened = false;
            PrintWriter writer = null;
            try {
                writer = new PrintWriter(sTraceFile);
                writer.println(sBuffer);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } finally {
                if (writer != null) {
                    writer.close();
                }
            }
        }
    }

    private static void reset() {
        sBuffer = new StringBuilder();
        sStartTime = System.currentTimeMillis();
    }

    private static void traceText(String text) {
        if (sBuffer != null) {
            sBuffer.append((System.currentTimeMillis() - sSkipTime - sStartTime));
            sBuffer.append(',');
            sBuffer.append(text);
            sBuffer.append('\n');
        }
    }

    public static class Playback {
        private final File mTraceFile;
        private boolean mCanBeUsed = true;
        private boolean mIsPlaying = false;
        private final TracedONScripterView mGame;
        private Thread mThread;

        public Playback(TracedONScripterView gameView, String tracePath) {
            mTraceFile = new File(tracePath);
            mGame = gameView;
        }

        public void start() {
            if (mCanBeUsed && !mIsPlaying && mThread == null) {
                mIsPlaying = true;

                mThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // Read file
                        ArrayList<String[]> commands = new ArrayList<String[]>();
                        BufferedReader br = null;
                        try {
                            br = new BufferedReader(new FileReader(mTraceFile));

                            // Parse the first line and check if we are loading any files
                            String line = br.readLine();
                            if (line != null) {
                                String[] parts = line.split(",");
                                if (parts[1].charAt(0) == LOAD_EVENT) {
                                    if (parts.length < 3) {
                                        toast("Playback cannot load because too little arguments");
                                        return;
                                    }

                                    // See if the save file is in the downloads folder
                                    File saveFile = new File(Environment.getExternalStoragePublicDirectory(
                                            Environment.DIRECTORY_DOWNLOADS) + "/" + parts[2]);
                                    if (!saveFile.exists()) {
                                        toast("Unable to playback without the save file in the downloads folder! (" + parts[2] + ")");
                                        return;
                                    }

                                    // Copy the save file from Downloads folder to game save folder as save1.dat
                                    File dst = new File(mGame.rootFolder + "/" + (parts.length >= 4 ? parts[3] : "") + "save1.dat");
                                    if (!copy(saveFile, dst)) {
                                        toast("Playback failed because could not copy save file");
                                        return;
                                    }

                                    // Load the game
                                    try {
                                        threadWait(2000);
                                        loadFirstGame();
                                    } catch (InterruptedException e1) {
                                        e1.printStackTrace();
                                        return;
                                    }
                                } else if (parts.length < 2) {
                                    toast("First line does not have enough arguments");
                                } else {
                                    commands.add(parts);
                                }
                            }

                            // Parse the rest of the file
                            while((line = br.readLine()) != null) {
                                if (!mIsPlaying) {
                                    return;
                                }
                                if (line.trim().length() > 0) {
                                    commands.add(line.split(","));
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            toast("Playback: Failed to read file");
                            return;
                        } finally {
                            try {
                                if (br != null) {
                                    br.close();
                                }
                            } catch (IOException e){}
                        }

                        // Playback the data
                        toast("Starting playback...");

                        long startTime = System.currentTimeMillis();
                        try {
                            for (String[] command: commands) {
                                long time = Long.parseLong(command[0]);
                                char type = command[1].charAt(0);

                                if (!mIsPlaying) {
                                    return;
                                }

                                // See when we should execute the next command
                                if (time + startTime > System.currentTimeMillis()) {
                                    try {
                                        threadWait(time + startTime - System.currentTimeMillis());
                                        if (!mIsPlaying) {
                                            return;
                                        }
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                        toast("Playback: Thread interruption");
                                    }
                                }

                                // Execute command
                                switch(type) {
                                case KEY_EVENT:
                                    int keyCode = Integer.parseInt(command[2]);
                                    int downState = Integer.parseInt(command[3]);
                                    mGame.triggerKeyEvent(keyCode, downState);
                                    Log.v("ONScripter Playback", "Key Event [" + time + "]: " + command[2] + ", " + command[3]);
                                    break;
                                case MOUSE_EVENT:
                                    int x = Integer.parseInt(command[2]);
                                    int y = Integer.parseInt(command[3]);
                                    int action = Integer.parseInt(command[4]);
                                    mGame.triggerMouseEvent(x, y, action);
                                    Log.v("ONScripter Playback", "Mouse Event [" + time + "]: (" + command[2] + "," + command[3] + "), " + command[4]);
                                    break;
                                case CRASH_EVENT:
                                    Log.v("ONScripter Playback", "Playback was logged to crash now");
                                    stop();
                                    return;
                                }
                            }
                        } catch (NumberFormatException e) {
                            e.printStackTrace();
                            toast("Plaback: Failed to parse numbers from file correctly");
                        } catch (ArrayIndexOutOfBoundsException e) {
                            e.printStackTrace();
                            toast("Playback: Failed to parse file because accessing data out of bounds");
                        }
                        stop();
                    }
                });
                mThread.start();
            }
        }

        public void stop() {
            if (mCanBeUsed && mIsPlaying && mThread != null) {
                mCanBeUsed = false;
                mIsPlaying = false;
                synchronized (mThread) {
                    mThread.notify();
                }
                toast("Finished playback");
            }
        }

        private void loadFirstGame() throws InterruptedException {
            toast("Loading save file");
            Log.v("ONScripter Playback", "Load first game");
            mGame.nativeKey(KeyEvent.KEYCODE_1, 1);
            mGame.nativeKey(KeyEvent.KEYCODE_1, 0);
            threadWait(500);
            mGame.nativeMouse(0, 0, 0);
            mGame.nativeMouse(0, 0, 1);
            threadWait(1000);
        }

        private boolean copy(File src, File dst) {
            OutputStream out = null;
            InputStream in = null;
            try {
                in = new FileInputStream(src);
                    out = new FileOutputStream(dst);

                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {}
                }
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {}
                }
            }
        }

        private void threadWait(long time) throws InterruptedException {
            synchronized (mThread) {
                Thread.currentThread().wait(time);
            }
        }

        private void toast(final String message) {
            ((Activity)mGame.getContext()).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(mGame.getContext(), message, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}