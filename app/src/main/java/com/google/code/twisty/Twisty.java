// Copyright 2009 Google Inc.
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package com.google.code.twisty;

// Note to developers: this Android activity is essentially a high-level consumer
// of the 'glkjni' project, which maps the classic GLK I/O API (used by game-
// interpreters to do UI) to JNI.  This allows us to run well-known C interpreters 
// as a native C library for maximum performance.  In particular, to build
// this project you'll need to get a copy of the glkjni code and have Eclipse link
// the 'roboglk' directory into your twisty project.  See the README file for a full
// explanation of how to build both the C and java code in this project.

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;

import org.brickshadow.roboglk.Glk;
import org.brickshadow.roboglk.GlkFactory;
import org.brickshadow.roboglk.GlkLayout;
import org.brickshadow.roboglk.GlkStyle;
import org.brickshadow.roboglk.GlkStyleHint;
import org.brickshadow.roboglk.GlkWinType;
import org.brickshadow.roboglk.io.StyleManager;
import org.brickshadow.roboglk.io.TextBufferIO;
import org.brickshadow.roboglk.util.UISync;
import org.brickshadow.roboglk.view.TextBufferView;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Gallery;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.RadioGroup.OnCheckedChangeListener;

import com.jakewharton.processphoenix.ProcessPhoenix;

public class Twisty extends Activity {
    private static String TAG = "Twisty";
    private static final int MENU_PICK_FILE = 101;
    private static final int MENU_STOP = 102;
    private static final int MENU_RESTART = 103;
    private static final int FILE_PICKED = 104;
    private static final int MENU_SHOW_HELP = 107;
    private static final int MENU_PICK_SETTINGS = 108;

    private static final int MENUGROUP_SELECT = 101;
    private static final int MENUGROUP_RUNNING = 102;

    String savegame_dir = "";
    String savefile_path = "";

    // Dialog boxes we manage
    private static final int DIALOG_ENTER_WRITEFILE = 1;
    private static final int DIALOG_ENTER_READFILE = 2;
    private static final int DIALOG_CHOOSE_GAME = 3;
    private static final int DIALOG_CANT_SAVE = 4;
    private static final int DIALOG_NO_SDCARD = 5;

    // Messages we receive from external threads, via our Handler
    public static final int PROMPT_FOR_WRITEFILE = 1;
    public static final int PROMPT_FOR_READFILE = 2;
    public static final int PROMPT_FOR_GAME = 3;

    // Permission request identifiers
    private final int MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 1;

    // Regular expression that matches all the file extensions we support
    public static final String EXTENSIONS = "(?i).+\\.(z[1-8]|blorb|zblorb|gblorb|ulx|blb|zlb|glb)$";

    // Regular expressions that match the file extensions supported by each interpreter
    public static final String NITFOL_EXTENSIONS = "(?i).+\\.(z[1-8]|zblorb|zlb)$";
    public static final String GIT_EXTENSIONS = "(?i).+\\.(blorb|gblorb|ulx|blb|glb)$";

    // The main GLK UI machinery.
    Glk glk;
    GlkLayout glkLayout;
    TextBufferIO mainWin;
    ImageView iv;
    TextBufferView tv;
    LinearLayout ll;
    Thread terpThread = null;
    String gamePath;
    Boolean gameIsRunning = false;

    // Passed down to TwistyGlk object, so terp thread can send Messages back to this thread
    private Handler dialog_handler;
    private Handler terp_handler;
    private TwistyMessage dialog_message; // most recent Message received
    // Persistent dialogs created in onCreateDialog() and updated by onPrepareDialog()
    private Dialog restoredialog;
    private Dialog choosegamedialog;
    // All z-games discovered when we last scanned the sdcard
    private String[] discovered_games;
    // A persistent map of button-ids to games found on the sdcard (absolute paths)
    private SparseArray<String> game_paths = new SparseArray<String>();
    private SparseArray<String> builtinGames = new SparseArray<String>();


    static class DialogHandler extends Handler {
        final WeakReference<Twisty> twisty;

        DialogHandler(Twisty twisty) {
            this.twisty = new WeakReference<Twisty>(twisty);
        }

        public void handleMessage(Message m) {
            twisty.get().savegame_dir = "";
            twisty.get().savefile_path = "";
            if (m.what == PROMPT_FOR_WRITEFILE) {
                twisty.get().dialog_message = (TwistyMessage) m.obj;
                twisty.get().promptForWritefile();
            }
            else if (m.what == PROMPT_FOR_READFILE) {
                twisty.get().dialog_message = (TwistyMessage) m.obj;
                twisty.get().promptForReadfile();
            }
            else if (m.what == PROMPT_FOR_GAME) {
                twisty.get().showDialog(DIALOG_CHOOSE_GAME);
            }
        }
    }

    static class TerpHandler extends Handler {
        final WeakReference<Twisty> twisty;

        TerpHandler(Twisty twisty) {
            this.twisty = new WeakReference<Twisty>(twisty);
        }

        public void handleMessage(Message m) {
            switch (m.arg1) {
            case -1:
               Log.i("twistyterp", "The interpreter did not start");
               break;
            case 0:
               Log.i("twistyterp", "The interpreter exited normally");
               break;
            case 1:
               Log.i("twistyterp", "The interpreter exited abnormally");
               break;
            case 2:
               Log.i("twistyterp", "The interpreter was interrupted");
               break;
            }
            twisty.get().showWelcome();
            twisty.get().gameIsRunning = false;
            }
    }

    /** Called when activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        // requestWindowFeature(Window.FEATURE_NO_TITLE);
        requestWindowFeature(Window.FEATURE_ACTION_BAR);

        // Map our built-in game resources to real filenames
        builtinGames.clear();
        builtinGames.put(R.raw.violet, "violet.z8");
        builtinGames.put(R.raw.rover, "rover.gblorb");
        builtinGames.put(R.raw.glulxercise, "glulxercise.ulx");
        builtinGames.put(R.raw.windowtest, "windowtest.ulx");

        UISync.setInstance(this);

        // An imageview to show the twisty icon
        iv = new ImageView(this);
        iv.setBackgroundColor(0xffffff);
        iv.setImageResource(R.drawable.app_icon);
        iv.setAdjustViewBounds(true);
        iv.setLayoutParams(new Gallery.LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT));

        // The main 'welcome screen' window from which games are launched.
        tv = new TextBufferView(this);
        mainWin = new TextBufferIO(tv, new StyleManager());
        //final GlkEventQueue eventQueue = null;
        tv.setFocusableInTouchMode(true);

        // The Glk window layout manager
        glkLayout = new GlkLayout(this);

        // put it all together
        ll = new LinearLayout(this);
        ll.setBackgroundColor(Color.argb(0xFF, 0xFE, 0xFF, 0xCC));
        ll.setOrientation(android.widget.LinearLayout.VERTICAL);
        ll.addView(iv);
        ll.addView(tv);
        glkLayout.setVisibility(View.GONE);
        ll.addView(glkLayout);
        setContentView(ll);

        dialog_handler = new DialogHandler(this);
        terp_handler = new TerpHandler(this);

        // Ensure we can write story files and save games to external storage
        checkWritePermission();

        Uri dataSource = this.getIntent().getData();
        if (dataSource != null) {
            /* Suck down the URI we received to sdcard, launch terp on it. */
            try {
                startTerp(dataSource);
            } catch (Exception e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
        else {
            printWelcomeMessage();
        }
    }

    /** Called whenever activity comes (or returns) to foreground. */
    @Override
    public void onStart() {
        super.onStart();
        getSettings();  // make sure user prefs are applied
    }

    private void printWelcomeMessage() {
        // What version of Twisty is running?
        PackageInfo pkginfo = null;
        try {
            pkginfo = this.getPackageManager().getPackageInfo("com.google.code.twisty", 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Couldn't determine Twisty version.");
        }

        StringBuffer battstate = new StringBuffer();
        appendBatteryState(battstate);

        mainWin.doStyle(GlkStyle.Normal);

        mainWin.doReverseVideo(true);
        mainWin.doPrint("Twisty " + pkginfo.versionName + "\nOpen-source software (C) Google Inc.\n\n\n");
        mainWin.doReverseVideo(false);
        mainWin.doPrint("You are holding a modern-looking mobile device which can be typed upon. ");
        mainWin.doPrint(battstate.toString() + " ");
        mainWin.doPrint("You feel an inexplicable urge to press the \"menu\" button.\n\n");
    }

    /* TODO:  rewrite this someday

    private void printHelpMessage() {
        if (zmIsRunning()) {
            Log.e(TAG, "Called printHelpMessage with ZM running");
            return;
        }

        ZWindow w = new ZWindow(screen, 0);
        w.set_text_style(ZWindow.ROMAN);
        w.newline();
        w.newline();
        w.bufferString("-------------------------------------");
        w.newline();
        w.bufferString("Concepts stream into your mind:");
        w.newline();
        w.newline();
        w.bufferString("Interactive Fiction (IF) is its own genre of game: an artful crossing ");
        w.bufferString("of storytelling and puzzle-solving. ");
        w.bufferString("Read the Wikipedia entry on 'Interactive ");
        w.bufferString("Fiction' to learn more.");
        w.newline();
        w.newline();
        w.bufferString("You are the protagonist of the story. Your job is to explore the ");
        w.bufferString("environment, interact with people and things, solve puzzles, and move ");
        w.bufferString("the story forward.  The interpreter is limited to a small set of ");
        w.bufferString("vocabulary, typically of the form 'verb noun'.  To get started:");
        w.newline();
        w.newline();
        w.bufferString("  * north, east, up, down, enter...");
        w.newline();
        w.bufferString("  * look, look under rug, examine pen");
        w.newline();
        w.bufferString("  * take ball, drop hat, inventory");
        w.newline();
        w.bufferString("  * Janice, tell me about the woodshed");
        w.newline();
        w.bufferString("  * save, restore");
        w.newline();
        w.newline();
        w.bufferString("For a more detailed tutorial, type 'help' inside the Curses or Anchorhead games.");
        w.newline();
        w.newline();
        w.bufferString("Twisty comes with three built-in games, but if you visit sites like ");
        w.bufferString("www.ifarchive.org or ifdb.tads.org, you can download ");
        w.bufferString("more games that end with either .z3, .z5, or .z8, copy them to your sdcard, then open them in Twisty.");
        w.newline();
        w.newline();
        w.flush();
    } */

    private void appendBatteryState(StringBuffer sb) {
        IntentFilter battFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent intent = registerReceiver(null, battFilter);

        int rawlevel = intent.getIntExtra("level", -1);
        int scale = intent.getIntExtra("scale", -1);
        int status = intent.getIntExtra("status", -1);
        int health = intent.getIntExtra("health", -1);
        int level = -1;  // percentage, or -1 for unknown
        if (rawlevel >= 0 && scale > 0) {
            level = (rawlevel * 100) / scale;
        }
        sb.append("The device");
        if (BatteryManager.BATTERY_HEALTH_OVERHEAT == health) {
            sb.append("'s battery feels very hot!");
        } else {
            switch(status) {
            case BatteryManager.BATTERY_STATUS_UNKNOWN:
                // old emulator; maybe also when plugged in with no battery
                sb.append(" has no battery.");
                break;
            case BatteryManager.BATTERY_STATUS_CHARGING:
                sb.append("'s battery");
                if (level <= 33)
                    sb.append(" is charging, and really ought to " +
                    "remain that way for the time being.");
                else if (level <= 84)
                    sb.append(" charges merrily.");
                else
                    sb.append(" will soon be fully charged.");
                break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                if (level == 0)
                    sb.append(" needs charging right away.");
                else if (level > 0 && level <= 33)
                    sb.append(" is about ready to be recharged.");
                else
                    sb.append("'s battery discharges merrily.");
                break;
            case BatteryManager.BATTERY_STATUS_FULL:
                sb.append(" is fully charged up and ready to go on " +
                "an adventure of some sort.");
                break;
            default:
                sb.append("'s battery is indescribable!");
            break;
            }
        }
        sb.append(" ");
    }

    protected void onActivityResult(int requestCode, int resultCode,
            String data, Bundle extras) {
        switch(requestCode) {
        case FILE_PICKED:
            if (resultCode == RESULT_OK && data != null) {
                // File picker completed
                Log.i(TAG, "Opening user-picked file: " + data);
                startTerp(data);
            }
            break;
        default:
            break;
        }
    }

    /** convenience helper to set text on a text view */
    void setItemText(final int id, final CharSequence text) {
        View v = findViewById(id);
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setText(text);
        }
    }

    // Show the GlkLayout and hide the welcome screen
    void showGlk() {
        iv.setVisibility(View.GONE);
        tv.setVisibility(View.GONE);
        glkLayout.setVisibility(View.VISIBLE);
    }

    // Show the welcome screen and hide the GlkLayout
    void showWelcome() {
        iv.setVisibility(View.VISIBLE);
        tv.setVisibility(View.VISIBLE);
        glkLayout.setVisibility(View.GONE);
    }

    /**
     * Start a terp thread, loading the program from the given game file
     * @param path Path to the gamefile to execute
     */
    void startTerp(String path) {

        // Notice user preferences
        //Context context = getApplicationContext();
        //SharedPreferences prefs =
        //	PreferenceManager.getDefaultSharedPreferences(context);
        //Log.i(TAG, "Spies tell me that ");

        // Switch to the Glk view
        showGlk();

        gamePath = path;

        // Make a GLK object which encapsulates I/O between Android UI and our C library
        glk = new TwistyGlk(this, glkLayout, dialog_handler);
        glk.setStyleHint(GlkWinType.AllTypes, GlkStyle.Normal, GlkStyleHint.Size, -2);
        terpThread = new Thread(new Runnable() {
               @Override
                public void run() {
                   String interpreter = "";
                   if (gamePath.matches(NITFOL_EXTENSIONS))
                       interpreter = "nitfol";
                   else if (gamePath.matches(GIT_EXTENSIONS))
                       interpreter = "git";

                   String[] args = new String[] {interpreter, gamePath};
                   int res = -1;
                   if (GlkFactory.startup(glk, args)) {
                       res = GlkFactory.run();
                   }
                   GlkFactory.shutdown();
                   Message m = terp_handler.obtainMessage();
                   m.arg1 = res;
                   terp_handler.sendMessage(m);

                   // Restarts the app. This ensures the interpreter libraries are reloaded. If we
                   // don't do this, GlkFactory.shutdown needs to assure for every library we
                   // integrate that its internal state is reset so that it behaves exactly the
                   // same as if it were started fresh, preferable without leaking memory. Since
                   // Glk doesn't specify this functionality, this means we'd need to modify each
                   // library to accommodate this. Reloading the library avoids this issue.
                   ProcessPhoenix.triggerRebirth(Twisty.this);
                }
            });
        terpThread.start();
        gameIsRunning = true;
    }


    /* Starts one of the 'built in' games from an android raw resource.
       It does this by dumping the resource into /sdcard/Twisty/ (if not already there.) */
    void startTerp(int resource) {

        String gameName = builtinGames.get(resource);  // go-go-autoboxing
        if (gameName == null) {
            Log.i(TAG, "Failed to find built-in game resource" + resource);
            return;
        }
        Log.i(TAG, "Loading game resource: " + gameName);

        String savedGamesDir = getSavedGamesDir(true);
        if (savedGamesDir == null) {
            showDialog(DIALOG_CANT_SAVE);
            return;
        }

        // The absolute disk path to the privately-stored gamefile:
        File gameFile = new File(savedGamesDir + "/" + gameName);
        String gamePath = gameFile.getAbsolutePath();
        Log.i(TAG, "Looking for gamefile at " + gamePath);

        if (! gameFile.exists()) {
            // Do a one-time dump from raw resource to disk.
            FileOutputStream foutstream;
            Resources r = new Resources(getAssets(), new DisplayMetrics(), null);
            InputStream gamestream = r.openRawResource(resource);
            try {
                foutstream = new FileOutputStream(gameFile);
            } catch (IOException e) {
                Log.i(TAG, "Failed to open outputstream for filename " + gamePath);
                Log.i(TAG, e.getMessage());
                return;
            }
            try {
                Log.i(TAG, "About to spew raw data to disk...");
                suckstream(gamestream, foutstream);
            } catch (IOException e) {
                Log.i(TAG, "Failed to copy raw game to file." + gamePath);
                return;
            }
            Log.i(TAG, "Completed dump of raw data to disk.");
        }

        Log.i(TAG, "Starting gamefile located at " + gamePath);
        startTerp(gamePath);
    }
    
    
    /* Starts a game located at a URI (usually http:// or content://) by downloading the game to sdcard first.
       This is the method invoked by our IntentFilter to handle story files coming from the web browser
       and other applications. */
    void startTerp(Uri gameURI) throws IOException, MalformedURLException {

        /* Set up output file in same directory as saved-games. */
        String dir = getSavedGamesDir(true);
        if (dir == null) {
            showDialog(DIALOG_CANT_SAVE);
            return;
        }

        String uriString = gameURI.toString();
        String gameFilename = uriString.substring(uriString.lastIndexOf("/") + 1);
        File outputFile = new File(dir, gameFilename);

        // Copy the input file into our story directory, unless the input and output file
        // would be the same. This check currently doesn't support uri's that don't contain
        // the file path. In that case the file will be corrupted.
        if (!((gameURI.getScheme().equals("content") || gameURI.getScheme().equals("file"))
                && gameURI.toString().contains(outputFile.getCanonicalPath()))) {
            FileOutputStream gameOutputStream = null;
            try {
                outputFile.createNewFile();
                gameOutputStream = new FileOutputStream(outputFile);
            } catch (IOException e) {
                Log.i(TAG, "Failed to create file called " + gameFilename);
                return;
            }


            /* Set up input from URI */
            InputStream gameInputStream = null;

            if(gameURI.getScheme().equals("content")) {
                try {
                    gameInputStream = getContentResolver().openInputStream(gameURI);
                } catch (FileNotFoundException e) {
                    Log.i(TAG, "Failed to open file: " + uriString);
                    gameOutputStream.close();
                    return;
                }
            }
            else {
                try {
                    URL gameURL = new URL(uriString);
                    URLConnection connection = gameURL.openConnection();
                    connection.connect();
                    gameInputStream = connection.getInputStream();
                } catch (MalformedURLException e) {
                    Log.i(TAG, "Received malformed URI: "+ uriString);
                    gameOutputStream.close();
                    return;
                } catch (IOException e) {
                    Log.i(TAG, "Failed to open connection to URI: " + uriString);
                    gameOutputStream.close();
                    return;
                }
            }

            try {
                Log.i(TAG, "About to spew raw data to disk...");
                suckstream(gameInputStream, gameOutputStream);
            } catch (IOException e) {
                Log.i(TAG, "Failed to copy URL contents to local file: " + uriString);
                return;
            }
            Log.i(TAG, "Completed dump of raw data to disk.");
        }
        else
            Log.i(TAG, "Input and output file are the same: " + outputFile.getCanonicalPath());

        Log.i(TAG, "Starting gamefile located at " + outputFile.getCanonicalPath());
        startTerp(outputFile.getCanonicalPath());
    }

    
    /* Helper for methods above:  suck all data from an InputStream and push to an OutputStream */
    void suckstream(InputStream instream, OutputStream outstream) throws IOException {
        int got = 0, buffersize = 65536;
        byte buffer[] = new byte[buffersize];
        while (true) {
            got = instream.read(buffer, 0, buffersize);
            if (got == -1)
                break; // got no data; end of stream
            outstream.write(buffer, 0, got);
        }
    }
    
    
    /** Convenience helper to set visibility of any view */
    void setViewVisibility(int id, int vis) {
        findViewById(id).setVisibility(vis);
    }


    private boolean terpIsRunning() {
        Log.i(TAG, "Query whether game is running!");
        return gameIsRunning;
    }


    /** Stops the currently running interpreter. */
    public void stopTerp() {
        if (terpThread != null) {
            terpThread.interrupt();
            Log.i(TAG, "Interrupted terpThread.");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        menu.add(MENUGROUP_SELECT, R.raw.violet, 0, "Violet").setShortcut('0', 'a');
        menu.add(MENUGROUP_SELECT, R.raw.rover, 1, "Rover").setShortcut('1', 'b');
        menu.add(MENUGROUP_SELECT, R.raw.glulxercise, 2, "glulxercise").setShortcut('2', 'c');
        menu.add(MENUGROUP_SELECT, R.raw.windowtest, 2, "windowtest").setShortcut('6', 'd');
        menu.add(MENUGROUP_SELECT, MENU_PICK_FILE, 3, "Open Game...").setShortcut('3', 'o');
        //menu.add(MENUGROUP_SELECT, MENU_SHOW_HELP, 5, "Help!?").setShortcut('4', 'h');
        menu.add(MENUGROUP_SELECT, MENU_PICK_SETTINGS, 5, "Settings").setShortcut('5', 's');

        //menu.add(MENUGROUP_RUNNING, MENU_RESTART, 0, "Restart").setShortcut('7', 'r');
        menu.add(MENUGROUP_RUNNING, MENU_STOP, 1, "Stop").setShortcut('9', 's');
        menu.add(MENUGROUP_RUNNING, MENU_PICK_SETTINGS, 2, "Settings").setShortcut('4', 's');

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu)
    {
        menu.setGroupVisible(MENUGROUP_SELECT, !terpIsRunning());
        menu.setGroupVisible(MENUGROUP_RUNNING, terpIsRunning());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        switch (item.getItemId()) {
        case MENU_RESTART:
            // TODO:  zm.restart();
            break;
        case MENU_STOP:
            stopTerp();
            // After the zmachine exits, the welcome message should show
            // again.
            break;
        case MENU_PICK_FILE:
            pickFile();
            break;
        case MENU_PICK_SETTINGS:
            pickSettings();
            break;
        case MENU_SHOW_HELP:
            // TODO:  printHelpMessage();
            break;
        default:
            startTerp(item.getItemId());
            break;
        }
        return super.onOptionsItemSelected(item);
    }


    /** Launch UI to adjust application settings **/
    private void pickSettings() {
         Intent settingsActivity = new Intent(
                 getBaseContext(), TwistyPreferenceActivity.class);
         startActivity(settingsActivity);
    }

    /** Called by onStart(), to make sure prefs are loaded whenever we return
     *  to the main Twisty activity (e.g. after backing out of the
     *  Preferences activity)
     */
    private void getSettings() {
         SharedPreferences prefs =
             PreferenceManager.getDefaultSharedPreferences(getBaseContext());
         String textSizePreference = prefs.getString("PREF_TEXT_SIZE", "14");
         float textsize = Float.valueOf(textSizePreference).floatValue();
         tv.setTextSize(textsize);
    }

    /** Launch UI to pick a file to load and execute */
    private void pickFile() {
        String storagestate = Environment.getExternalStorageState();
        if (storagestate.equals(Environment.MEDIA_MOUNTED)
                || storagestate.equals(Environment.MEDIA_MOUNTED_READ_ONLY)) {
            final ProgressDialog pd = ProgressDialog.show(Twisty.this,
                    "Scanning Media", "Searching for Games...", true);
            Thread t = new Thread() {
                public void run() {
                    // populate our list of games:
                    discovered_games = scanForGames();
                    pd.dismiss();
                    Message msg = new Message();
                    msg.what = PROMPT_FOR_GAME;
                    dialog_handler.sendMessage(msg);
                }
            };
            t.start();
        }
        else
            showDialog(DIALOG_NO_SDCARD); // no sdcard to scan
    }


    // Return the path to the saved-games directory (typically "/sdcard/Twisty/")
    // If sdcard not present, or if /sdcard/Twisty is a file, return null.
    public static String getSavedGamesDir(boolean write) {
        String storagestate = Environment.getExternalStorageState();
        if (!storagestate.equals(Environment.MEDIA_MOUNTED) &&
                (write || storagestate.equals(Environment.MEDIA_MOUNTED_READ_ONLY))) {
            return null;
        }
        String sdpath = Environment.getExternalStorageDirectory().getPath();
        File savedir = new File(sdpath + "/" + TAG);
        if (! savedir.exists()) {
            savedir.mkdirs();
        }
        else if (! savedir.isDirectory()) {
            return null;
        }
        return savedir.getPath();
    }

    // Helper for scanForGames():
    // Walk DIR recursively, adding any file matching the expression in EXTENSIONS to LIST.
    private void scanDir(File dir, ArrayList<String> list) {
        File[] children = dir.listFiles();
        if (children == null)
            return;
        for (int count = 0; count < children.length; count++) {
            File child = children[count];
            if (child.isFile() && child.getName().matches(EXTENSIONS))
                list.add(child.getPath());
            else
                scanDir(child, list);
        }
    }

    // Search the twisty directory (on sdcard) for any games.
    // Return an array of absolute paths, or null on failure.
    private String[] scanForGames() {
        String gamesDirPath = getSavedGamesDir(false);
        if (gamesDirPath == null) {
            showDialog(DIALOG_CANT_SAVE);
            return null;
        }
        File gameDirRoot = new File(gamesDirPath);
        ArrayList<String> gamelist = new ArrayList<String>();
        scanDir(gameDirRoot, gamelist);
        String[] files = gamelist.toArray(new String[gamelist.size()]);
        Arrays.sort(files);
        return files;
    }

    public void promptForWritefile() {
        String dir = getSavedGamesDir(true);
        if (dir == null) {
            showDialog(DIALOG_CANT_SAVE);
            return;
        }
        savegame_dir = dir;
        showDialog(DIALOG_ENTER_WRITEFILE);
    }

    private void promptForReadfile() {
        String dir = getSavedGamesDir(false);
        if (dir == null) {
            showDialog(DIALOG_CANT_SAVE);
            return;
        }
        savegame_dir = dir;
        showDialog(DIALOG_ENTER_READFILE);
    }

    // Used by 'Restore Game' dialog box;  scans /sdcard/twisty and updates
    // the list of radiobuttons.
    private void updateRestoreRadioButtons(RadioGroup rg) {
        rg.removeAllViews();
        int id = 0;
        String[] gamelist  = new File(savegame_dir).list();
        for (String filename : gamelist) {
            RadioButton rb = new RadioButton(Twisty.this);
            rb.setText(filename);
            rg.addView(rb);
            id = rb.getId();
        }
        rg.check(id); // by default, check the last item
    }

    // Used by 'Choose Game' dialog box;  scans all of /sdcard for games,
    // updates list of radiobuttons (and the game_paths HashMap).
    private void updateGameRadioButtons(RadioGroup rg) {
        rg.removeAllViews();
        game_paths.clear();
        int id = 0;
        for (String path : discovered_games) {
            RadioButton rb = new RadioButton(Twisty.this);
            rb.setText(new File(path).getName());
            rg.addView(rb);
            id = rb.getId();
            game_paths.put(id, path);
        }
    }

    /** Have our activity manage and persist dialogs, showing and hiding them */
    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {

        case DIALOG_ENTER_WRITEFILE:
            LayoutInflater factory = LayoutInflater.from(this);
            final View textEntryView = factory.inflate(R.layout.save_file_prompt, null);
            final EditText et = (EditText) textEntryView.findViewById(R.id.savefile_entry);
            return new AlertDialog.Builder(Twisty.this)
            .setTitle("Write to file")
            .setView(textEntryView)
            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    savefile_path = savegame_dir + "/" + et.getText().toString();
                    // Directly modify the message-object passed to us by the terp thread:
                    dialog_message.path = savefile_path;
                    // Wake up the terp thread again
                    synchronized (glkLayout) {
                        glkLayout.notify();
                    }
                }
            })
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    // This makes op_save() fail.
                    dialog_message.path = "";
                    // Wake up the terp thread again
                    synchronized (glkLayout) {
                        glkLayout.notify();
                    }
                }
            })
            .create();

        case DIALOG_ENTER_READFILE:
            restoredialog = new Dialog(Twisty.this);
            restoredialog.setContentView(R.layout.restore_file_prompt);
            restoredialog.setTitle("Read a file");
            android.widget.RadioGroup rg = (RadioGroup) restoredialog.findViewById(R.id.radiomenu);
            updateRestoreRadioButtons(rg);
            android.widget.Button okbutton = (Button) restoredialog.findViewById(R.id.restoreokbutton);
            okbutton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    android.widget.RadioGroup rg = (RadioGroup) restoredialog.findViewById(R.id.radiomenu);
                    int checkedid = rg.getCheckedRadioButtonId();
                    if (rg.getChildCount() == 0) {  // no saved games:  FAIL
                        savefile_path = "";
                    } else	if (checkedid == -1) { // no game selected
                        RadioButton firstbutton = (RadioButton) rg.getChildAt(0); // default to first game
                        savefile_path = savegame_dir + "/" + firstbutton.getText();
                    } else {
                        RadioButton checkedbutton = (RadioButton) rg.findViewById(checkedid);
                        savefile_path = savegame_dir + "/" + checkedbutton.getText();
                    }
                    dismissDialog(DIALOG_ENTER_READFILE);
                    // Return control to the z-machine thread
                    dialog_message.path = savefile_path;
                    synchronized (glkLayout) {
                        glkLayout.notify();
                    }
                }
            });
            android.widget.Button cancelbutton = (Button) restoredialog.findViewById(R.id.restorecancelbutton);
            cancelbutton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dismissDialog(DIALOG_ENTER_READFILE);
                    // Return control to the z-machine thread
                    dialog_message.path = "";
                    synchronized (glkLayout) {
                        glkLayout.notify();
                    }
                }
            });
            return restoredialog;

        case DIALOG_CHOOSE_GAME:
            choosegamedialog = new Dialog(Twisty.this);
            choosegamedialog.setContentView(R.layout.choose_game_prompt);
            choosegamedialog.setTitle("Choose Game");
            android.widget.RadioGroup zrg = (RadioGroup) choosegamedialog.findViewById(R.id.game_radiomenu);
            updateGameRadioButtons(zrg);
            zrg.setOnCheckedChangeListener(new OnCheckedChangeListener() {
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    dismissDialog(DIALOG_CHOOSE_GAME);
                    String path = (String) game_paths.get(checkedId);
                    if (path != null) {
                        stopTerp();
                        startTerp(path);
                    }
                }
            });
            return choosegamedialog;

        case DIALOG_CANT_SAVE:
            return new AlertDialog.Builder(Twisty.this)
            .setTitle("Cannot Access Games")
            .setMessage("Twisty Games folder is not available on external media.")
            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    // A path of "" makes op_save() fail.
                    dialog_message.path = "";
                    // Wake up the terp thread again
                    synchronized (glkLayout) {
                        glkLayout.notify();
                    }
                }
            })
            .create();

        case DIALOG_NO_SDCARD:
            return new AlertDialog.Builder(Twisty.this)
            .setTitle("No External Media")
            .setMessage("Cannot find sdcard or other media.")
            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    // do nothing
                }
            })
            .create();
        }
        return null;
    }


    /** Have our activity prepare dialogs before displaying them */
    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        super.onPrepareDialog(id, dialog);
        switch(id) {
        case DIALOG_ENTER_READFILE:
            android.widget.RadioGroup rg = (RadioGroup) restoredialog.findViewById(R.id.radiomenu);
            updateRestoreRadioButtons(rg);
            break;
        case DIALOG_CHOOSE_GAME:
            android.widget.RadioGroup zrg = (RadioGroup) choosegamedialog.findViewById(R.id.game_radiomenu);
            updateGameRadioButtons(zrg);
            break;
        }
    }

    public void showMore(boolean show) {
        setViewVisibility(R.id.more, show ? View.VISIBLE : View.GONE);
    }

/*
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        // Restore saved view state
        super.onRestoreInstanceState(savedInstanceState);
        // TODO: restore zmachine state
    }
*/

    @Override
    protected void onSaveInstanceState(Bundle bundle) {
        // Hopefully this will save all the view states:
        super.onSaveInstanceState(bundle);
    }

/*
    private boolean unfreezeZM(Bundle icicle) {
        if (icicle == null)
            return false;
        Log.i(TAG, "unfreeze: finished");
        return true;
    }
*/

    void checkWritePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE: {
                if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Write permission granted");
                }
                else {
                    // TODO: do something more user-friendly here. Twisty will currently silently
                    // fail on operations that require the permission when it isn't granted.
                    Log.i(TAG, "User did not grant write permission");
                }
                break;
            }
        }
    }
}
